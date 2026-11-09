package com.share.share.controller;

import com.share.common.api.ErrorCode;
import com.share.common.api.Result;
import com.share.common.audit.AuditAspect;
import com.share.common.exception.BizException;
import com.share.share.service.OpenApiService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享服务开放调用接口（FR-07，/api/open/v1/{apiCode}）。
 * <p><b>无需 JWT</b>（已在鉴权白名单），使用请求头 {@code X-App-Key} + {@code X-App-Secret} 校验凭据；
 * 按凭据 QPS 做令牌桶限流（超限返回 HTTP 429 + SHARE_QPS_LIMITED）；
 * 返回字段按凭据授权密级做动态脱敏；每次调用写 {@code shr_call_stat} 计量。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/open/v1")
public class OpenApiController {

    private final OpenApiService openApiService;

    /** GET 方式开放调用，支持 pageNum/pageSize 与 API 定义的自定义参数过滤。 */
    @GetMapping("/{apiCode}")
    public ResponseEntity<Result<Map<String, Object>>> get(@PathVariable String apiCode,
                                                           @RequestHeader(value = "X-App-Key", required = false) String appKey,
                                                           @RequestHeader(value = "X-App-Secret", required = false) String appSecret,
                                                           @RequestHeader(value = "X-Api-Version", required = false) String apiVersion,
                                                           @RequestParam(required = false) Map<String, String> query,
                                                           HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (query != null) {
            params.putAll(query);
        }
        return invoke(apiCode, params, appKey, appSecret, apiVersion, request);
    }

    /** POST 方式开放调用，参数放在 JSON body（同样支持 pageNum/pageSize）。 */
    @PostMapping("/{apiCode}")
    public ResponseEntity<Result<Map<String, Object>>> post(@PathVariable String apiCode,
                                                            @RequestHeader(value = "X-App-Key", required = false) String appKey,
                                                            @RequestHeader(value = "X-App-Secret", required = false) String appSecret,
                                                            @RequestHeader(value = "X-Api-Version", required = false) String apiVersion,
                                                            @RequestBody(required = false) Map<String, Object> body,
                                                            HttpServletRequest request) {
        Map<String, Object> params = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        return invoke(apiCode, params, appKey, appSecret, apiVersion, request);
    }

    private ResponseEntity<Result<Map<String, Object>>> invoke(String apiCode, Map<String, Object> params,
                                                               String appKey, String appSecret,
                                                               String apiVersion,
                                                               HttpServletRequest request) {
        int pageNum = takeInt(params, "pageNum", 1);
        int pageSize = takeInt(params, "pageSize", 10);
        String ip = AuditAspect.clientIp(request);
        try {
            Map<String, Object> data = openApiService.invoke(apiCode, params, pageNum, pageSize,
                    appKey, appSecret, apiVersion, ip);
            long total = data.get("total") instanceof Number n ? n.longValue() : 0L;
            return ResponseEntity.ok(Result.ok(data, total, pageNum, pageSize));
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.SHARE_QPS_LIMITED.getCode()) {
                // 限流语义要求 HTTP 429
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Result.fail(e.getCode(), e.getMessage()));
            }
            if (e.getCode() == ErrorCode.SHARE_API_CIRCUIT_OPEN.getCode()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Result.fail(e.getCode(), e.getMessage()));
            }
            return ResponseEntity.ok(Result.fail(e.getCode(), e.getMessage()));
        }
    }

    /** 取出并移除分页参数，剩余键作为业务过滤条件。 */
    private int takeInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.remove(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
