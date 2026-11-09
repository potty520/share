package com.share.auth.controller;

import com.share.auth.dto.AdminRequests;
import com.share.auth.dto.VOs;
import com.share.auth.service.UserService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（FR-13）：分页查询、新增、修改、删除、启停、重置口令。
 */
@RestController
@RequestMapping("/api/user/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 用户分页列表：keyword 匹配用户名/姓名/手机号。 */
    @GetMapping
    @RequirePermission(PermissionConstants.USER_READ)
    public Result<java.util.List<VOs.UserVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        VOs.PageResult<VOs.UserVO> page = userService.list(keyword, deptId, roleCode, status, pageNum, pageSize);
        return Result.ok(page.list(), page.total(), pageNum, pageSize);
    }

    /** 用户详情。 */
    @GetMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_READ)
    public Result<VOs.UserVO> get(@PathVariable Long id) {
        return Result.ok(userService.get(id));
    }

    /** 新增用户。 */
    @PostMapping
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "USER_CREATE", targetType = "USER", recordArgs = false)
    public Result<VOs.UserVO> create(@Valid @RequestBody AdminRequests.UserSaveRequest request) {
        return Result.ok(userService.create(request));
    }

    /** 修改用户。 */
    @PutMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "USER_UPDATE", targetType = "USER", recordArgs = false)
    public Result<VOs.UserVO> update(@PathVariable Long id,
                                     @Valid @RequestBody AdminRequests.UserSaveRequest request) {
        return Result.ok(userService.update(id, request));
    }

    /** 删除用户（逻辑删除）。 */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "USER_DELETE", targetType = "USER")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.ok();
    }

    /** 启用 / 停用。 */
    @PutMapping("/{id}/status")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "USER_STATUS", targetType = "USER")
    public Result<VOs.UserVO> updateStatus(@PathVariable Long id,
                                           @RequestBody AdminRequests.StatusRequest request) {
        return Result.ok(userService.updateStatus(id, request.status()));
    }

    /** 重置口令（不传 password 时重置为 123456）。 */
    @PutMapping("/{id}/reset-password")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "USER_RESET_PASSWORD", targetType = "USER", recordArgs = false)
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody(required = false) AdminRequests.ResetPasswordRequest request) {
        userService.resetPassword(id, request == null ? null : request.password());
        return Result.ok();
    }
}
