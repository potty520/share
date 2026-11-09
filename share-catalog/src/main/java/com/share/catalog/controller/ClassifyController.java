package com.share.catalog.controller;

import com.share.catalog.dto.ClassifyDTO;
import com.share.catalog.dto.ClassifyVO;
import com.share.catalog.service.ClassifyService;
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
 * 目录分类树接口（FR-03）。
 * <p>路径前缀 {@code /api/catalog/v1/classifies}；GET 返回树（flat=true 返回平铺列表）。</p>
 */
@RestController("catalogClassifyController")
@RequestMapping("/api/catalog/v1/classifies")
@RequiredArgsConstructor
public class ClassifyController {

    private final ClassifyService classifyService;

    /** 分类树：dimension=SUBJECT/DEPT/DOMAIN 可选。 */
    @GetMapping
    @RequirePermission(PermissionConstants.CATALOG_READ)
    public Result<List<ClassifyVO>> tree(@RequestParam(required = false) String dimension,
                                         @RequestParam(defaultValue = "false") boolean flat) {
        return Result.ok(classifyService.tree(dimension, flat));
    }

    /** 分类节点详情（含直接子节点）。 */
    @GetMapping("/{id}")
    @RequirePermission(PermissionConstants.CATALOG_READ)
    public Result<ClassifyVO> detail(@PathVariable Long id) {
        return Result.ok(classifyService.detail(id));
    }

    /** 新增分类节点。 */
    @PostMapping
    @RequirePermission(PermissionConstants.CATALOG_WRITE)
    @AuditLog(action = "CLASSIFY_CREATE", targetType = "CLASSIFY")
    public Result<ClassifyVO> create(@Valid @RequestBody ClassifyDTO dto) {
        return Result.ok(classifyService.create(dto));
    }

    /** 修改分类节点。 */
    @PutMapping("/{id}")
    @RequirePermission(PermissionConstants.CATALOG_WRITE)
    @AuditLog(action = "CLASSIFY_UPDATE", targetType = "CLASSIFY")
    public Result<ClassifyVO> update(@PathVariable Long id, @RequestBody ClassifyDTO dto) {
        return Result.ok(classifyService.update(id, dto));
    }

    /** 删除分类节点（存在子节点或已挂接目录时拒绝）。 */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionConstants.CATALOG_WRITE)
    @AuditLog(action = "CLASSIFY_DELETE", targetType = "CLASSIFY")
    public Result<Void> delete(@PathVariable Long id) {
        classifyService.delete(id);
        return Result.ok();
    }
}
