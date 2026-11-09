package com.share.task.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.common.security.UserContext;
import com.share.task.dto.RunTicket;
import com.share.task.service.InstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务实例与批次接口（FR-04）：全局实例列表/详情/日志/重跑/一致性核对、批次流转查询。
 */
@RestController
@RequestMapping("/api/task/v1")
@RequiredArgsConstructor
public class InstanceController {

    private final InstanceService instanceService;

    /** 全局实例列表。 */
    @GetMapping("/instances")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<List<Map<String, Object>>> instances(@RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(required = false) Long taskId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String triggerType,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        Page<Map<String, Object>> page = instanceService.pageInstances(pageNum, pageSize, taskId, status,
                triggerType, keyword, from, to);
        return Result.ok(page.getContent(), page.getTotalElements(), pageNum, pageSize);
    }

    /** 实例详情（实例 + 批次 + 最近日志）。 */
    @GetMapping("/instances/{id}")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(instanceService.detail(id));
    }

    /** 实例运行日志（分页；tail=true 取尾部）。 */
    @GetMapping("/instances/{id}/logs")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<List<Map<String, Object>>> logs(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "1") int pageNum,
                                                  @RequestParam(defaultValue = "20") int pageSize,
                                                  @RequestParam(defaultValue = "false") boolean tail) {
        Page<Map<String, Object>> page = instanceService.pageLogs(id, pageNum, pageSize, tail);
        return Result.ok(page.getContent(), page.getTotalElements(), pageNum, pageSize);
    }

    /** 重跑实例。 */
    @PostMapping("/instances/{id}/rerun")
    @RequirePermission(PermissionConstants.TASK_RUN)
    @AuditLog(action = "TASK_RERUN", targetType = "TASK")
    public Result<RunTicket> rerun(@PathVariable Long id) {
        return Result.ok(instanceService.rerun(id, UserContext.userId(), UserContext.username()));
    }

    /** 一致性核对（行数 + MD5 与批次记录比对）。 */
    @PostMapping("/instances/{id}/verify")
    @RequirePermission(PermissionConstants.TASK_RUN)
    @AuditLog(action = "TASK_VERIFY", targetType = "TASK")
    public Result<Map<String, Object>> verify(@PathVariable Long id) {
        return Result.ok(instanceService.verify(id));
    }

    /** 批次查询（含流转日志）；不传 batchId 时返回最近批次。 */
    @GetMapping("/batches")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> batches(@RequestParam(required = false) String batchId) {
        return Result.ok(instanceService.batches(batchId));
    }
}
