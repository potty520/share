package com.share.catalog.controller;

import com.share.catalog.dto.AuditRequest;
import com.share.catalog.dto.CatalogDTO;
import com.share.catalog.dto.CatalogQuery;
import com.share.catalog.dto.CatalogStatsVO;
import com.share.catalog.dto.CatalogVO;
import com.share.catalog.dto.CatalogVersionVO;
import com.share.catalog.dto.PreviewVO;
import com.share.catalog.service.CatalogService;
import com.share.catalog.service.PreviewService;
import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据目录接口（FR-03）。
 * <p>路径前缀 {@code /api/catalog/v1}；状态机 DRAFT → PENDING → PUBLISHED / REJECTED，PUBLISHED → OFFLINE。</p>
 */
@RestController
@RequestMapping("/api/catalog/v1")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    /** 目录检索（默认只查已发布；all=true 查全部状态）。 */
    @GetMapping("/resources")
    @RequirePermission(PermissionConstants.CATALOG_READ)
    public Result<List<CatalogVO>> list(@ModelAttribute CatalogQuery query) {
        Page<CatalogVO> page = catalogService.page(query);
        return Result.ok(page.getContent(), page.getTotalElements(), query.getPageNum(), query.getPageSize());
    }

    /** 目录详情（含数据项、历史版本、挂接信息）。 */
    @GetMapping("/resources/{id}")
    @RequirePermission(PermissionConstants.CATALOG_READ)
    public Result<CatalogVO> detail(@PathVariable Long id) {
        return Result.ok(catalogService.detail(id));
    }

    /** 新增目录（DRAFT，数据项 items 随资源提交）。 */
    @PostMapping("/resources")
    @RequirePermission(PermissionConstants.CATALOG_WRITE)
    @AuditLog(action = "CATALOG_CREATE", targetType = "CATALOG")
    public Result<CatalogVO> create(@RequestBody CatalogDTO dto) {
        return Result.ok(catalogService.create(dto));
    }

    /** 修改目录（发布后修改自动生成新版本）。 */
    @PutMapping("/resources/{id}")
    @RequirePermission(PermissionConstants.CATALOG_WRITE)
    @AuditLog(action = "CATALOG_UPDATE", targetType = "CATALOG")
    public Result<CatalogVO> update(@PathVariable Long id, @RequestBody CatalogDTO dto) {
        return Result.ok(catalogService.update(id, dto));
    }

    /** 删除目录（逻辑删除）。 */
    @DeleteMapping("/resources/{id}")
    @RequirePermission(PermissionConstants.CATALOG_WRITE)
    @AuditLog(action = "CATALOG_DELETE", targetType = "CATALOG")
    public Result<Void> delete(@PathVariable Long id) {
        catalogService.delete(id);
        return Result.ok();
    }

    /** 提交审核（DRAFT→PENDING）。 */
    @PostMapping("/resources/{id}/submit")
    @RequirePermission(PermissionConstants.CATALOG_WRITE)
    @AuditLog(action = "CATALOG_SUBMIT", targetType = "CATALOG")
    public Result<CatalogVO> submit(@PathVariable Long id) {
        return Result.ok(catalogService.submit(id));
    }

    /** 审核（PENDING→PUBLISHED/REJECTED，发布时写版本快照）。 */
    @PostMapping("/resources/{id}/audit")
    @RequirePermission(PermissionConstants.CATALOG_PUBLISH)
    @AuditLog(action = "CATALOG_AUDIT", targetType = "CATALOG")
    public Result<CatalogVO> audit(@PathVariable Long id, @Valid @RequestBody AuditRequest request) {
        return Result.ok(catalogService.audit(id, request));
    }

    /** 下架（PUBLISHED→OFFLINE）。 */
    @PostMapping("/resources/{id}/offline")
    @RequirePermission(PermissionConstants.CATALOG_PUBLISH)
    @AuditLog(action = "CATALOG_OFFLINE", targetType = "CATALOG")
    public Result<CatalogVO> offline(@PathVariable Long id) {
        return Result.ok(catalogService.offline(id));
    }

    /** 历史版本列表。 */
    @GetMapping("/resources/{id}/versions")
    @RequirePermission(PermissionConstants.CATALOG_READ)
    public Result<List<CatalogVersionVO>> versions(@PathVariable Long id) {
        return Result.ok(catalogService.versions(id));
    }

    /** 样例数据预览（挂接库表前 N 行；敏感字段脱敏）。 */
    @GetMapping("/resources/{id}/preview")
    @RequirePermission(PermissionConstants.CATALOG_READ)
    public Result<PreviewVO> preview(@PathVariable Long id,
                                     @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = limit <= 0 ? PreviewService.DEFAULT_LIMIT : Math.min(limit, PreviewService.MAX_LIMIT);
        return Result.ok(catalogService.preview(id, safeLimit));
    }

    /** 目录统计（总数/已发布/待审核/涉敏 + 按部门分布）。 */
    @GetMapping("/stats")
    @RequirePermission(PermissionConstants.CATALOG_READ)
    public Result<CatalogStatsVO> stats() {
        return Result.ok(catalogService.stats());
    }
}
