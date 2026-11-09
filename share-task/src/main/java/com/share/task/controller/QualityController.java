package com.share.task.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.common.security.UserContext;
import com.share.task.dto.QualityRuleRequest;
import com.share.task.service.QualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
import java.util.Map;

/**
 * 数据质量接口（FR-06）：规则 CRUD、执行检核、报告查询、问题工单闭环。
 */
@RestController
@RequestMapping("/api/quality/v1")
@RequiredArgsConstructor
public class QualityController {

    private final QualityService qualityService;

    /** 规则列表。 */
    @GetMapping("/rules")
    @RequirePermission(PermissionConstants.QUALITY_READ)
    public Result<List<Map<String, Object>>> rules(@RequestParam(defaultValue = "1") int pageNum,
                                                   @RequestParam(defaultValue = "10") int pageSize,
                                                   @RequestParam(required = false) String ruleType,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Boolean enabled) {
        Page<Map<String, Object>> page = qualityService.pageRules(pageNum, pageSize, ruleType, keyword, enabled);
        return Result.ok(page.getContent(), page.getTotalElements(), pageNum, pageSize);
    }

    /** 新建规则。 */
    @PostMapping("/rules")
    @RequirePermission(PermissionConstants.QUALITY_WRITE)
    @AuditLog(action = "QUALITY_RULE_CREATE", targetType = "QUALITY_RULE")
    public Result<Map<String, Object>> createRule(@RequestBody QualityRuleRequest request) {
        return Result.ok(qualityService.createRule(request));
    }

    /** 修改规则。 */
    @PutMapping("/rules/{id}")
    @RequirePermission(PermissionConstants.QUALITY_WRITE)
    @AuditLog(action = "QUALITY_RULE_UPDATE", targetType = "QUALITY_RULE")
    public Result<Map<String, Object>> updateRule(@PathVariable Long id, @RequestBody QualityRuleRequest request) {
        return Result.ok(qualityService.updateRule(id, request));
    }

    /** 删除规则（逻辑删除）。 */
    @DeleteMapping("/rules/{id}")
    @RequirePermission(PermissionConstants.QUALITY_WRITE)
    @AuditLog(action = "QUALITY_RULE_DELETE", targetType = "QUALITY_RULE")
    public Result<Void> deleteRule(@PathVariable Long id) {
        qualityService.deleteRule(id);
        return Result.ok();
    }

    /** 执行检核并生成报告/工单。 */
    @PostMapping("/rules/{id}/run")
    @RequirePermission(PermissionConstants.QUALITY_WRITE)
    @AuditLog(action = "QUALITY_RULE_RUN", targetType = "QUALITY_RULE")
    public Result<Map<String, Object>> runRule(@PathVariable Long id) {
        return Result.ok(qualityService.runRule(id, UserContext.username()));
    }

    /** 报告列表。 */
    @GetMapping("/reports")
    @RequirePermission(PermissionConstants.QUALITY_READ)
    public Result<List<Map<String, Object>>> reports(@RequestParam(defaultValue = "1") int pageNum,
                                                     @RequestParam(defaultValue = "10") int pageSize,
                                                     @RequestParam(required = false) Long ruleId,
                                                     @RequestParam(required = false) String targetTable) {
        Page<Map<String, Object>> page = qualityService.pageReports(pageNum, pageSize, ruleId, targetTable);
        return Result.ok(page.getContent(), page.getTotalElements(), pageNum, pageSize);
    }

    /** 报告详情（含问题工单）。 */
    @GetMapping("/reports/{id}")
    @RequirePermission(PermissionConstants.QUALITY_READ)
    public Result<Map<String, Object>> reportDetail(@PathVariable Long id) {
        return Result.ok(qualityService.reportDetail(id));
    }

    /** 问题工单列表。 */
    @GetMapping("/issues")
    @RequirePermission(PermissionConstants.QUALITY_READ)
    public Result<List<Map<String, Object>>> issues(@RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "10") int pageSize,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long ruleId,
                                                    @RequestParam(required = false) String level) {
        Page<Map<String, Object>> page = qualityService.pageIssues(pageNum, pageSize, status, ruleId, level);
        return Result.ok(page.getContent(), page.getTotalElements(), pageNum, pageSize);
    }

    /** 问题工单整改闭环。 */
    @PutMapping("/issues/{id}/close")
    @RequirePermission(PermissionConstants.QUALITY_WRITE)
    @AuditLog(action = "QUALITY_ISSUE_CLOSE", targetType = "QUALITY_ISSUE")
    public Result<Map<String, Object>> closeIssue(@PathVariable Long id,
                                                  @RequestParam(required = false) String remark,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        String closeRemark = remark;
        if ((closeRemark == null || closeRemark.isBlank()) && body != null && body.get("remark") != null) {
            closeRemark = String.valueOf(body.get("remark"));
        }
        return Result.ok(qualityService.closeIssue(id, closeRemark, UserContext.username()));
    }
}
