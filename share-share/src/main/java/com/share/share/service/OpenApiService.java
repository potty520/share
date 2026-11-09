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
    private final ClusterRateLimitService clusterRateLimitService;
    private final CircuitBreakerService circuitBreakerService;
    private final IpAccessService ipAccessService;
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
                                      String appKey, String appSecret, String apiVersion, String ip) {
        long start = System.currentTimeMillis();
        ShareApi api = apiRepository.findByApiCodeAndDeletedFalse(apiCode)
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_API_NOT_FOUND, "共享服务不存在: " + apiCode));
        // 1. 凭据鉴权（校验失败不落计量，避免伪造凭据刷库）
        ShareCredential credential = credentialService.authenticate(appKey, appSecret);
        // 2. 来源 IP 黑白名单（黑名单优先，白名单为空不限制）
        if (!ipAccessService.allowed(ip, credential.getIpWhitelist(), credential.getIpBlacklist())) {
            try {
                callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN, 0,
                        ShareConstants.STAT_FAIL, 0, ip, "来源 IP 被拒绝: " + ip);
            } catch (Exception ignore) {
                // 计量失败不影响拒绝语义
            }
            throw new BizException(ErrorCode.SHARE_IP_DENIED, ip);
        }
        // 3. 服务上线状态校验
        if (!ShareConstants.API_ONLINE.equals(api.getStatus())) {
            throw new BizException(ErrorCode.SHARE_API_NOT_ONLINE, "共享服务当前状态为 " + api.getStatus());
        }
        // 4. 版本校验：调用方显式指定 X-Api-Version 时必须与当前发布版本一致
        if (apiVersion != null && !apiVersion.isBlank()
                && !apiVersion.trim().equalsIgnoreCase(api.getApiVersion())) {
            throw new BizException(ErrorCode.SHARE_API_VERSION_MISMATCH,
                    "共享服务版本不匹配：当前 " + api.getApiVersion() + "，请求 " + apiVersion);
        }
        // 5. 熔断检查（窗口内连续失败后快速失败）
        if (!circuitBreakerService.allow(api.getApiCode())) {
            try {
                callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN,
                        System.currentTimeMillis() - start, ShareConstants.STAT_FAIL, 0, ip, "服务已熔断");
            } catch (Exception ignore) {
                // 计量失败不影响熔断语义
            }
            throw new BizException(ErrorCode.SHARE_API_CIRCUIT_OPEN, "共享服务已熔断，请稍后重试");
        }
        // 6. 集群级 + 单机令牌桶双重限流（按凭据 QPS）
        Integer qps = credential.getQpsLimit() == null ? api.getQpsLimit() : credential.getQpsLimit();
        int effectiveQps = qps == null || qps <= 0 ? 1 : qps;
        boolean clusterAllowed = clusterRateLimitService.tryAcquire(credential.getAppKey(), effectiveQps);
        boolean localAllowed = rateLimitService.tryAcquire(credential.getAppKey(), effectiveQps);
        if (!clusterAllowed || !localAllowed) {
            try {
                callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN,
                        System.currentTimeMillis() - start, ShareConstants.STAT_FAIL, 0, ip, "超过 QPS 限额");
            } catch (Exception ignore) {
                // 计量失败不影响限流语义
            }
            throw new BizException(ErrorCode.SHARE_QPS_LIMITED,
                    "超过凭据 QPS 限额（" + effectiveQps + " req/s）");
        }
        // 7. 动态脱敏判定：凭据授权密级 < 数据密级 → 强制脱敏
        int requiredLevel = crossDomainQueryService.catalogLevel(api.getCatalogId());
        boolean mask = maskingHelper.shouldMask(credential.getGrantLevel(), requiredLevel, false);
        try {
            DataQueryService.QueryResult result = dataQueryService.query(api, params, pageNum, pageSize, mask);
            circuitBreakerService.recordSuccess(api.getApiCode());
            callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN, result.costMs(),
                    ShareConstants.STAT_SUCCESS, result.rows().size(), ip, null);
            credentialService.touchCall(credential);
            touchApi(api);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("apiId", api.getId());
            data.put("apiCode", api.getApiCode());
            data.put("apiName", api.getApiName());
            data.put("apiVersion", api.getApiVersion());
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
            if (e.getCode() == ErrorCode.BUSINESS.getCode() || e.getCode() == ErrorCode.INTERNAL.getCode()) {
                circuitBreakerService.recordFailure(api.getApiCode());
            }
            callStatService.record(api, credential, ShareConstants.CHANNEL_OPEN,
                    System.currentTimeMillis() - start, ShareConstants.STAT_FAIL, 0, ip, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            circuitBreakerService.recordFailure(api.getApiCode());
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
