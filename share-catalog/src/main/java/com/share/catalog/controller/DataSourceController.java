package com.share.catalog.controller;

import com.share.catalog.dto.DataSourceDTO;
import com.share.catalog.dto.DataSourceLogVO;
import com.share.catalog.dto.DataSourceQuery;
import com.share.catalog.dto.DataSourceVO;
import com.share.catalog.dto.SourceTestResult;
import com.share.catalog.dto.StatusRequest;
import com.share.catalog.service.DataSourceService;
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
import java.util.Map;

/**
 * 数据源接入管理接口（FR-01）。
 * <p>路径前缀 {@code /api/datasource/v1}；口令类字段一律掩码返回，变更均记审计。</p>
 */
@RestController
@RequestMapping("/api/datasource/v1")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceService dataSourceService;

    /** 数据源列表（分页，支持关键词/类型/状态/部门过滤）。 */
    @GetMapping("/sources")
    @RequirePermission(PermissionConstants.DATASOURCE_READ)
    public Result<List<DataSourceVO>> list(@ModelAttribute DataSourceQuery query) {
        Page<DataSourceVO> page = dataSourceService.page(query);
        return Result.ok(page.getContent(), page.getTotalElements(), query.getPageNum(), query.getPageSize());
    }

    /** 数据源详情（口令掩码）。 */
    @GetMapping("/sources/{id}")
    @RequirePermission(PermissionConstants.DATASOURCE_READ)
    public Result<DataSourceVO> detail(@PathVariable Long id) {
        return Result.ok(dataSourceService.detail(id));
    }

    /** 新增数据源（口令 AES-GCM 加密落库）。口令敏感，审计不记录入参明文。 */
    @PostMapping("/sources")
    @RequirePermission(PermissionConstants.DATASOURCE_WRITE)
    @AuditLog(action = "DATASOURCE_CREATE", targetType = "DATASOURCE", recordArgs = false)
    public Result<DataSourceVO> create(@Valid @RequestBody DataSourceDTO dto) {
        return Result.ok(dataSourceService.create(dto));
    }

    /** 修改数据源。口令敏感，审计不记录入参明文。 */
    @PutMapping("/sources/{id}")
    @RequirePermission(PermissionConstants.DATASOURCE_WRITE)
    @AuditLog(action = "DATASOURCE_UPDATE", targetType = "DATASOURCE", recordArgs = false)
    public Result<DataSourceVO> update(@PathVariable Long id, @RequestBody DataSourceDTO dto) {
        return Result.ok(dataSourceService.update(id, dto));
    }

    /** 删除数据源（逻辑删除，保留变更历史）。 */
    @DeleteMapping("/sources/{id}")
    @RequirePermission(PermissionConstants.DATASOURCE_WRITE)
    @AuditLog(action = "DATASOURCE_DELETE", targetType = "DATASOURCE")
    public Result<Void> delete(@PathVariable Long id) {
        dataSourceService.delete(id);
        return Result.ok();
    }

    /** 连通性测试（真实 TCP / 路径 / URL 探测）。 */
    @PostMapping("/sources/{id}/test")
    @RequirePermission(PermissionConstants.DATASOURCE_READ)
    @AuditLog(action = "DATASOURCE_TEST", targetType = "DATASOURCE")
    public Result<SourceTestResult> test(@PathVariable Long id) {
        return Result.ok(dataSourceService.test(id));
    }

    /** 启用/停用数据源。 */
    @PutMapping("/sources/{id}/status")
    @RequirePermission(PermissionConstants.DATASOURCE_WRITE)
    @AuditLog(action = "DATASOURCE_STATUS", targetType = "DATASOURCE")
    public Result<DataSourceVO> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return Result.ok(dataSourceService.changeStatus(id, request));
    }

    /** 变更历史（分页）。 */
    @GetMapping("/sources/{id}/history")
    @RequirePermission(PermissionConstants.DATASOURCE_READ)
    public Result<List<DataSourceLogVO>> history(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "1") int pageNum,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        Page<DataSourceLogVO> page = dataSourceService.history(id, pageNum, pageSize);
        return Result.ok(page.getContent(), page.getTotalElements(), pageNum, pageSize);
    }

    /** 数据源类型字典（含默认端口/驱动/连接串模板）。 */
    @GetMapping("/types")
    @RequirePermission(PermissionConstants.DATASOURCE_READ)
    public Result<List<Map<String, Object>>> types() {
        return Result.ok(dataSourceService.types());
    }
}
