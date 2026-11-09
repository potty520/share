package com.share.auth.controller;

import com.share.auth.dto.AdminRequests;
import com.share.auth.dto.VOs;
import com.share.auth.service.RoleService;
import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与权限分配接口（FR-13 RBAC）。
 */
@RestController
@RequestMapping("/api/user/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** 角色列表（含权限编码与用户数）。 */
    @GetMapping
    @RequirePermission(PermissionConstants.USER_READ)
    public Result<List<VOs.RoleVO>> list() {
        return Result.ok(roleService.list());
    }

    /** 角色详情。 */
    @GetMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_READ)
    public Result<VOs.RoleVO> get(@PathVariable Long id) {
        return Result.ok(roleService.get(id));
    }

    /** 新增角色。 */
    @PostMapping
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "ROLE_CREATE", targetType = "ROLE")
    public Result<VOs.RoleVO> create(@Valid @RequestBody AdminRequests.RoleSaveRequest request) {
        return Result.ok(roleService.create(request));
    }

    /** 修改角色。 */
    @PutMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "ROLE_UPDATE", targetType = "ROLE")
    public Result<VOs.RoleVO> update(@PathVariable Long id,
                                     @Valid @RequestBody AdminRequests.RoleSaveRequest request) {
        return Result.ok(roleService.update(id, request));
    }

    /** 删除角色。 */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "ROLE_DELETE", targetType = "ROLE")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.ok();
    }

    /** 权限分配（全量覆盖）。 */
    @PutMapping("/{id}/permissions")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "ROLE_PERMISSION_ASSIGN", targetType = "ROLE")
    public Result<VOs.RoleVO> assignPermissions(@PathVariable Long id,
                                                @RequestBody AdminRequests.PermissionAssignRequest request) {
        return Result.ok(roleService.assignPermissions(id, request.permissionCodes()));
    }
}
