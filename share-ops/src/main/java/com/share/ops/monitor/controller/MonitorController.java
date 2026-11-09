package com.share.ops.monitor.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.ops.monitor.entity.MonAlarmRule;
import com.share.ops.monitor.service.AlarmService;
import com.share.ops.monitor.service.MonitorService;
import com.share.ops.support.ResultSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 监控告警接口（FR-12）：平台总览、节点状态、指标趋势、告警规则与记录、运维报表。
 */
@RestController
@RequestMapping("/api/monitor/v1")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;
    private final AlarmService alarmService;

    /** 平台总览：节点在线数/任务成功率/今日交换行数/API 今日调用量/未确认告警数 */
    @GetMapping("/overview")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<Map<String, Object>> overview() {
        return Result.ok(monitorService.overview());
    }

    /** 节点状态（含 CPU/内存/磁盘与心跳超时判定） */
    @GetMapping("/nodes")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<Map<String, Object>> nodes() {
        return Result.ok(monitorService.nodes());
    }

    /** 指标趋势：type=EXCHANGE|API|NODE，按小时聚合 */
    @GetMapping("/metrics")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<Map<String, Object>> metrics(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.ok(monitorService.metrics(type, from, to));
    }

    // ================= 告警规则 =================

    @GetMapping("/alarm-rules")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<List<Map<String, Object>>> listRules(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String metricType,
                                                       @RequestParam(required = false) Boolean enabled,
                                                       @RequestParam(required = false) Integer pageNum,
                                                       @RequestParam(required = false) Integer pageSize) {
        // 未传分页参数时返回全量规则（前端配置页需要完整列表）
        if (pageNum == null && pageSize == null) {
            return Result.ok(alarmService.listRules(metricType, enabled));
        }
        return ResultSupport.paged(alarmService.pageRules(keyword, metricType, enabled, pageNum, pageSize));
    }

    @GetMapping("/alarm-rules/{id}")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<Map<String, Object>> ruleDetail(@PathVariable Long id) {
        return Result.ok(alarmService.ruleDetail(id));
    }

    @PostMapping("/alarm-rules")
    @RequirePermission(PermissionConstants.MONITOR_WRITE)
    @AuditLog(action = "ALARM_RULE_CREATE", targetType = "ALARM_RULE")
    public Result<Map<String, Object>> createRule(@RequestBody MonAlarmRule request) {
        return Result.ok(alarmService.createRule(request));
    }

    @PutMapping("/alarm-rules/{id}")
    @RequirePermission(PermissionConstants.MONITOR_WRITE)
    @AuditLog(action = "ALARM_RULE_UPDATE", targetType = "ALARM_RULE")
    public Result<Map<String, Object>> updateRule(@PathVariable Long id, @RequestBody MonAlarmRule request) {
        return Result.ok(alarmService.updateRule(id, request));
    }

    @DeleteMapping("/alarm-rules/{id}")
    @RequirePermission(PermissionConstants.MONITOR_WRITE)
    @AuditLog(action = "ALARM_RULE_DELETE", targetType = "ALARM_RULE")
    public Result<Void> deleteRule(@PathVariable Long id) {
        alarmService.deleteRule(id);
        return Result.ok();
    }

    // ================= 告警记录 =================

    /** 告警记录分页（level/status/metricType/ruleId/时间范围） */
    @GetMapping("/alarms")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<List<Map<String, Object>>> alarms(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String metricType,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return ResultSupport.paged(alarmService.pageAlarms(level, status, metricType, ruleId, keyword,
                from, to, pageNum, pageSize));
    }

    @GetMapping("/alarms/{id}")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<Map<String, Object>> alarmDetail(@PathVariable Long id) {
        return Result.ok(alarmService.alarmDetail(id));
    }

    /** 确认告警 */
    @PostMapping("/alarms/{id}/ack")
    @RequirePermission(PermissionConstants.MONITOR_WRITE)
    @AuditLog(action = "ALARM_ACK", targetType = "ALARM")
    public Result<Map<String, Object>> ack(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String remark = body == null || body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return Result.ok(alarmService.ack(id, remark));
    }

    /** 运维报表：日/周/月交换量、成功率、部门共享排行、任务健康度 */
    @GetMapping("/reports")
    @RequirePermission(PermissionConstants.MONITOR_READ)
    public Result<Map<String, Object>> reports(@RequestParam(defaultValue = "day") String period) {
        return Result.ok(monitorService.reports(period));
    }
}
