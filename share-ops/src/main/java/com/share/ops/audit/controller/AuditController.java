package com.share.ops.audit.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.ops.audit.service.AuditService;
import com.share.ops.support.ResultSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全审计接口（FR-11）：检索、详情、统计、数据流转追踪、异常行为、CSV 导出。
 */
@RestController
@RequestMapping("/api/audit/v1")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;
    private final com.share.ops.audit.service.ElasticsearchAuditService elasticsearchAuditService;

    /** 审计检索：operator/action/module/result/时间范围 + 关键词 */
    @GetMapping("/logs")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    public Result<List<Map<String, Object>>> logs(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) Long minCostMs,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return ResultSupport.paged(auditService.page(operator, action, module, result, keyword,
                operatorId, minCostMs, from, to, pageNum, pageSize));
    }

    @GetMapping("/logs/{id}")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(auditService.detail(id));
    }

    /** 审计统计：按模块/动作/结果/操作人统计 + 日趋势 */
    @GetMapping("/stats")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    public Result<Map<String, Object>> stats(@RequestParam(required = false) Integer days) {
        return Result.ok(auditService.stats(days));
    }

    /** 数据流转追踪：某批次 源 → 任务 → 目标 */
    @GetMapping("/trace")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    public Result<Map<String, Object>> trace(@RequestParam String batchId) {
        return Result.ok(auditService.trace(batchId));
    }

    /** 异常行为：非工作时间、短时高频、连续失败 */
    @GetMapping("/anomalies")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    public Result<Map<String, Object>> anomalies() {
        return Result.ok(auditService.anomalies());
    }

    /** 手工触发审计日志保留期清理（mode=LOGICAL/ARCHIVE/PHYSICAL）。 */
    @PostMapping("/retention/cleanup")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    @AuditLog(action = "AUDIT_RETENTION_CLEANUP", targetType = "AUDIT")
    public Result<Map<String, Object>> cleanupRetention(@RequestParam(required = false) Integer days,
                                                        @RequestParam(required = false) String mode) {
        return Result.ok(auditService.cleanupExpired(days, mode));
    }

    /** 审计检索后端状态（Elasticsearch / MySQL）。 */
    @GetMapping("/search/status")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    public Result<Map<String, Object>> searchStatus() {
        return Result.ok(elasticsearchAuditService.status());
    }

    /** 审计报表导出（CSV，UTF-8 BOM 便于 Excel 直接打开） */
    @GetMapping("/export")
    @RequirePermission(PermissionConstants.AUDIT_READ)
    @AuditLog(action = "AUDIT_EXPORT", targetType = "AUDIT", recordArgs = false)
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) Long minCostMs,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        String csv = auditService.exportCsv(operator, action, module, result, keyword,
                operatorId, minCostMs, from, to);
        byte[] content = csv.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[content.length + 3];
        body[0] = (byte) 0xEF;
        body[1] = (byte) 0xBB;
        body[2] = (byte) 0xBF;
        System.arraycopy(content, 0, body, 3, content.length);
        String fileName = "audit-log-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
