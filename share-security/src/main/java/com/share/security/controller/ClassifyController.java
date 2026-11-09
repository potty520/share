package com.share.security.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.security.dto.SecurityRequests;
import com.share.security.dto.SecurityVOs;
import com.share.security.entity.SecClassifyTag;
import com.share.security.entity.SecClassifyTemplate;
import com.share.security.service.ClassifyService;
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
 * 分类分级接口（FR-10）：1~4 级模板 CRUD + 目录/字段分级标签（单个、批量）。
 */
@RestController("securityClassifyController")
@RequestMapping("/api/security/v1")
@RequiredArgsConstructor
public class ClassifyController {

    private final ClassifyService classifyService;

    // ==================== 分级模板 ====================

    /** 模板分页列表。 */
    @GetMapping("/templates")
    @RequirePermission(PermissionConstants.SECURITY_READ)
    public Result<List<SecClassifyTemplate>> listTemplates(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        SecurityVOs.PageResult<SecClassifyTemplate> page =
                classifyService.listTemplates(keyword, status, pageNum, pageSize);
        return Result.ok(page.list(), page.total(), pageNum, pageSize);
    }

    /** 模板详情。 */
    @GetMapping("/templates/{id}")
    @RequirePermission(PermissionConstants.SECURITY_READ)
    public Result<SecClassifyTemplate> getTemplate(@PathVariable Long id) {
        return Result.ok(classifyService.getTemplate(id));
    }

    /** 新增模板（levelDefs 为 1~4 级定义 JSON）。 */
    @PostMapping("/templates")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_TEMPLATE_CREATE", targetType = "CLASSIFY_TEMPLATE")
    public Result<SecClassifyTemplate> createTemplate(@RequestBody SecurityRequests.TemplateSaveRequest request) {
        return Result.ok(classifyService.createTemplate(request));
    }

    /** 修改模板。 */
    @PutMapping("/templates/{id}")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_TEMPLATE_UPDATE", targetType = "CLASSIFY_TEMPLATE")
    public Result<SecClassifyTemplate> updateTemplate(@PathVariable Long id,
                                                      @RequestBody SecurityRequests.TemplateSaveRequest request) {
        return Result.ok(classifyService.updateTemplate(id, request));
    }

    /** 删除模板。 */
    @DeleteMapping("/templates/{id}")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_TEMPLATE_DELETE", targetType = "CLASSIFY_TEMPLATE")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        classifyService.deleteTemplate(id);
        return Result.ok();
    }

    // ==================== 分级标签 ====================

    /** 分级标签分页查询：目录详情等业务页面需要展示分级标签，登录用户均可读。 */
    @GetMapping("/tags")
    public Result<List<SecClassifyTag>> listTags(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        SecurityVOs.PageResult<SecClassifyTag> page =
                classifyService.listTags(targetType, targetId, level, keyword, pageNum, pageSize);
        return Result.ok(page.list(), page.total(), pageNum, pageSize);
    }

    /** 单个目标打标。 */
    @PostMapping("/tags")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_TAG_CREATE", targetType = "CLASSIFY_TAG")
    public Result<SecClassifyTag> tag(@RequestBody SecurityRequests.TagSaveRequest request) {
        return Result.ok(classifyService.tag(request));
    }

    /** 批量打标。 */
    @PostMapping("/tags/batch")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_TAG_BATCH", targetType = "CLASSIFY_TAG")
    public Result<List<SecClassifyTag>> batchTag(@RequestBody SecurityRequests.TagBatchRequest request) {
        return Result.ok(classifyService.batchTag(request));
    }

    /** 删除分级标签。 */
    @DeleteMapping("/tags/{id}")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_TAG_DELETE", targetType = "CLASSIFY_TAG")
    public Result<Void> deleteTag(@PathVariable Long id) {
        classifyService.deleteTag(id);
        return Result.ok();
    }
}
