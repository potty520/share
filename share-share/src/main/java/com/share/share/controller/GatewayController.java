package com.share.share.controller;

import com.share.common.api.Result;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.share.service.CircuitBreakerService;
import com.share.share.service.ClusterRateLimitService;
import com.share.share.service.ApisixGatewayService;
import com.share.share.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关运维接口（FR-07）：熔断状态观测与应急复位。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/share/v1/gateway")
public class GatewayController {

    private final CircuitBreakerService circuitBreakerService;
    private final RateLimitService rateLimitService;
    private final ClusterRateLimitService clusterRateLimitService;
    private final ApisixGatewayService apisixGatewayService;

    /** 熔断器与限流桶运行状态。 */
    @GetMapping("/status")
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("circuits", circuitBreakerService.snapshot());
        data.put("rateLimitBuckets", rateLimitService.bucketCount());
        data.put("rateLimitBackend", rateLimitService.backend());
        data.put("clusterLimitBackend", clusterRateLimitService.backend());
        return Result.ok(data);
    }

    /** 应急复位熔断器（不传 apiCode 时复位全部）。 */
    @PostMapping("/circuit/reset")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    public Result<Map<String, Object>> reset(@RequestParam(required = false) String apiCode) {
        circuitBreakerService.reset(apiCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reset", apiCode == null ? "ALL" : apiCode);
        return Result.ok(data);
    }

    /** APISIX 连接与插件状态。 */
    @GetMapping("/apisix/status")
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<Map<String, Object>> apisixStatus() {
        return Result.ok(apisixGatewayService.status());
    }

    /** 全量下发共享 API 路由到 APISIX。 */
    @PostMapping("/apisix/sync")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    public Result<Map<String, Object>> apisixSync() {
        return Result.ok(apisixGatewayService.syncAll());
    }
}
