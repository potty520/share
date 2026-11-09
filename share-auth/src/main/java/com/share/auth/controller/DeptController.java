package com.share.auth.controller;

import com.share.auth.dto.AdminRequests;
import com.share.auth.dto.VOs;
import com.share.auth.service.DeptService;
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

import java.util.List;

/**
 * 组织机构接口（FR-13）：部门树、扁平列表与增删改。
 */
@RestController
@RequestMapping("/api/user/v1/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    /** 部门树（flat=true 返回扁平列表，供下拉框使用）：登录用户均可读，供各业务页面选择归口部门。 */
    @GetMapping
    public Result<List<VOs.DeptNode>> list(@RequestParam(defaultValue = "false") boolean flat) {
        return Result.ok(flat ? deptService.flat() : deptService.tree());
    }

    /** 部门详情（登录用户均可读）。 */
    @GetMapping("/{id}")
    public Result<VOs.DeptNode> get(@PathVariable Long id) {
        return Result.ok(deptService.get(id));
    }

    /** 新增部门。 */
    @PostMapping
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "DEPT_CREATE", targetType = "DEPT")
    public Result<VOs.DeptNode> create(@Valid @RequestBody AdminRequests.DeptSaveRequest request) {
        return Result.ok(deptService.create(request));
    }

    /** 修改部门。 */
    @PutMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "DEPT_UPDATE", targetType = "DEPT")
    public Result<VOs.DeptNode> update(@PathVariable Long id,
                                       @Valid @RequestBody AdminRequests.DeptSaveRequest request) {
        return Result.ok(deptService.update(id, request));
    }

    /** 删除部门。 */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionConstants.USER_WRITE)
    @AuditLog(action = "DEPT_DELETE", targetType = "DEPT")
    public Result<Void> delete(@PathVariable Long id) {
        deptService.delete(id);
        return Result.ok();
    }
}
