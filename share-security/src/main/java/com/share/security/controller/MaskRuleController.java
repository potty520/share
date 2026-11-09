package com.share.security.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.security.dto.SecurityRequests;
import com.share.security.dto.SecurityVOs;
import com.share.security.entity.SecMaskRule;
import com.share.security.service.MaskRuleService;
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
 * 脱敏规则接口（FR-10）：规则 CRUD + 试算。
 */
@RestController
@RequestMapping("/api/security/v1/mask-rules")
@RequiredArgsConstructor
public class MaskRuleController {

    private final MaskRuleService maskRuleService;

    /** 规则分页列表。 */
    @GetMapping
    @RequirePermission(PermissionConstants.SECURITY_READ)
    public Result<List<SecMaskRule>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String maskType,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        SecurityVOs.PageResult<SecMaskRule> page =
                maskRuleService.list(keyword, maskType, enabled, pageNum, pageSize);
        return Result.ok(page.list(), page.total(), pageNum, pageSize);
    }

    /** 规则详情。 */
    @GetMapping("/{id}")
    @RequirePermission(PermissionConstants.SECURITY_READ)
    public Result<SecMaskRule> get(@PathVariable Long id) {
        return Result.ok(maskRuleService.get(id));
    }

    /** 新增规则。 */
    @PostMapping
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_MASK_RULE_CREATE", targetType = "MASK_RULE")
    public Result<SecMaskRule> create(@RequestBody SecurityRequests.MaskRuleSaveRequest request) {
        return Result.ok(maskRuleService.create(request));
    }

    /** 修改规则。 */
    @PutMapping("/{id}")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_MASK_RULE_UPDATE", targetType = "MASK_RULE")
    public Result<SecMaskRule> update(@PathVariable Long id,
                                      @RequestBody SecurityRequests.MaskRuleSaveRequest request) {
        return Result.ok(maskRuleService.update(id, request));
    }

    /** 删除规则。 */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_MASK_RULE_DELETE", targetType = "MASK_RULE")
    public Result<Void> delete(@PathVariable Long id) {
        maskRuleService.delete(id);
        return Result.ok();
    }

    /** 规则试算：{maskType,param,value,ruleCode?} → 脱敏结果与耗时。 */
    @PostMapping("/test")
    @RequirePermission(PermissionConstants.SECURITY_READ)
    @AuditLog(action = "SECURITY_MASK_RULE_TEST", targetType = "MASK_RULE", recordArgs = false)
    public Result<SecurityVOs.MaskTestResult> test(@RequestBody SecurityRequests.MaskTestRequest request) {
        return Result.ok(maskRuleService.test(request));
    }
}
