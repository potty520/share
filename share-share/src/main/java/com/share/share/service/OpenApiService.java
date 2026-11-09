package com.share.share.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.share.constant.ShareConstants;
import com.share.share.entity.ShareApi;
import com.share.share.entity.ShareCredential;
import com.share.share.repository.ShareApiRepository;
import com.share.share.support.CrossDomainQueryService;
import com.share.share.support.ShareUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放调用服务（FR-07 /api/open/v1/{apiCode}，无需 JWT）。
 * <p>调用链：AppKey 鉴权 → 服务上线状态校验 → 令牌桶限流（按凭据 QPS）→ 参数白名单过滤查询
 * → 动态脱敏（凭据授权密级低于数据密级时强制脱敏）→ 计量落库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiService {

    private final ShareApiRepository apiRepository;
    private final ShareCredentialService credentialService;
    private final RateLimitService rateLimitService;
    private final DataQueryService dataQueryService;
    private final ShareCallStatService callStatService;
    private final MaskingHelper maskingHelper;
    private final CrossDomainQueryService crossDomainQueryService;

    /**
     * 执行一次开放调用。
     *
     * @param apiCode   服务编码
     * @param params    查询参数（自定义参数名）
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param appKey    请求头 X-App-Key
     * @param appSecret 请求头 X-App-Secret
     * @param ip        调用方 IP
     */
    public Map<String, Object> invoke(String apiCode, Map<String, Object> params, int pageNum, int pageSize,
                                      String appKey, String appSecret, String ip) {
        long start = System.currentTimeMillis();
        ShareApi api = apiRepository.findByApiCodeAndDeletedFalse(apiCode)
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_API_NOT_FOUND, "共享服务不存在: " + apiCode));
        // 1. 凭据鉴权（校验失败不落计量，避免伪造凭据刷库）
        ShareCredential credential = credentialService.authenticate(appKey, appSecret);
        // 2. 服务上线状态校验
        if (!ShareConstants.API_ONLINE.equals(api.getStatus())) {
            throw new BizException(ErrorCode.SHARE_API_NOT_ONLINE, "共享服务当前状态为 " + api.getStatus());
        }
        // 3. 令牌桶限流（按凭据 QPS）
        Integer qps = credential.getQpsLimit() == null ? api.getQpsLimit() : credential.getQpsLimit();
        if (!rateLimitService.tryAcquire(credential.getAppKey(), qps)) {
            try {
                callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN,
                        System.currentTimeMillis() - start, ShareConstants.STAT_FAIL, 0, ip, "超过 QPS 限额");
            } catch (Exception ignore) {
                // 计量失败不影响限流语义
            }
            throw new BizException(ErrorCode.SHARE_QPS_LIMITED,
                    "超过凭据 QPS 限额（" + (qps == null ? 1 : qps) + " req/s）");
        }
        // 4. 动态脱敏判定：凭据授权密级 < 数据密级 → 强制脱敏
        int requiredLevel = crossDomainQueryService.catalogLevel(api.getCatalogId());
        boolean mask = maskingHelper.shouldMask(credential.getGrantLevel(), requiredLevel, false);
        try {
            DataQueryService.QueryResult result = dataQueryService.query(api, params, pageNum, pageSize, mask);
            callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN, result.costMs(),
                    ShareConstants.STAT_SUCCESS, result.rows().size(), ip, null);
            credentialService.touchCall(credential);
            touchApi(api);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("apiId", api.getId());
            data.put("apiCode", api.getApiCode());
            data.put("apiName", api.getApiName());
            data.put("catalogName", api.getCatalogName());
            data.put("consumerName", credential.getConsumerName());
            data.put("appKey", credential.getAppKey());
            data.put("pageNum", result.pageNum());
            data.put("pageSize", result.pageSize());
            data.put("total", result.total());
            data.put("list", result.rows());
            data.put("masked", mask);
            data.put("maskedFields", result.maskedFields());
            data.put("grantLevel", credential.getGrantLevel());
            data.put("requiredLevel", requiredLevel);
            data.put("costMs", result.costMs());
            data.put("callTime", LocalDateTime.now());
            data.put("qpsLimit", qps);
            data.put("requestParams", params);
            return data;
        } catch (BizException e) {
            callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN,
                    System.currentTimeMillis() - start, ShareConstants.STAT_FAIL, 0, ip, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN,
                    System.currentTimeMillis() - start, ShareConstants.STAT_FAIL, 0, ip, e.getMessage());
            throw new BizException(ErrorCode.BUSINESS, "开放调用失败: " + e.getMessage());
        }
    }

    private void touchApi(ShareApi api) {
        api.setCallCount((api.getCallCount() == null ? 0L : api.getCallCount()) + 1);
        api.setLastCallTime(LocalDateTime.now());
        apiRepository.save(api);
    }
}
