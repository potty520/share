package com.share.auth.controller;

import com.share.auth.dto.VOs;
import com.share.auth.service.OverviewService;
import com.share.auth.service.PermissionCatalog;
import com.share.common.api.Result;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限点字典与用户概览接口（FR-13）。
 */
@RestController
@RequestMapping("/api/user/v1")
@RequiredArgsConstructor
public class PermissionController {

    private final OverviewService overviewService;

    /** 全部权限点字典（按模块分组展示用）。 */
    @GetMapping("/permissions")
    @RequirePermission(PermissionConstants.USER_READ)
    public Result<List<VOs.PermissionVO>> permissions() {
        return Result.ok(PermissionCatalog.toVOs());
    }

    /** 用户/角色/部门统计（工作台）：登录即可查看，便于各角色工作台展示。 */
    @GetMapping("/overview")
    public Result<VOs.Overview> overview() {
        return Result.ok(overviewService.overview());
    }
}
