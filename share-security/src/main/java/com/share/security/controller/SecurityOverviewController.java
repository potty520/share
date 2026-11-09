package com.share.security.controller;

import com.share.common.api.Result;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.security.dto.SecurityVOs;
import com.share.security.service.SecurityOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全域概览接口（FR-10）：分级分布、规则数、密钥数、敏感资源数。
 */
@RestController
@RequestMapping("/api/security/v1")
@RequiredArgsConstructor
public class SecurityOverviewController {

    private final SecurityOverviewService overviewService;

    @GetMapping("/overview")
    @RequirePermission(PermissionConstants.SECURITY_READ)
    public Result<SecurityVOs.Overview> overview() {
        return Result.ok(overviewService.overview());
    }
}
