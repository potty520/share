package com.share.task.controller;

import com.share.common.api.PageQuery;
import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.common.security.UserContext;
import com.share.task.dto.BatchEnableRequest;
import com.share.task.dto.DepRequest;
import com.share.task.dto.RunTicket;
import com.share.task.dto.ScheduleRequest;
import com.share.task.dto.TaskRequest;
import com.share.task.service.InstanceService;
import com.share.task.service.ScheduleService;
import com.share.task.service.TaskDepService;
import com.share.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
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
 * 交换任务接口（FR-04）：任务 CRUD、详情、启停、手工触发/终止、依赖编排、调度设置、批量启停。
 */
@RestController
@RequestMapping("/api/task/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    private final TaskDepService taskDepService;

    private final InstanceService instanceService;

    private final ScheduleService scheduleService;

    /** 任务分页列表。 */
    @GetMapping
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<List<Map<String, Object>>> list(@Validated PageQuery pageQuery,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String taskType,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Boolean enabled,
                                                  @RequestParam(required = false) Long deptId) {
        Page<Map<String, Object>> page = taskService.page(pageQuery.getPageNum(), pageQuery.getPageSize(),
                keyword, taskType, status, enabled, deptId);
        return Result.ok(page.getContent(), page.getTotalElements(), pageQuery.getPageNum(), pageQuery.getPageSize());
    }

    /** 新建任务。 */
    @PostMapping
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_CREATE", targetType = "TASK")
    public Result<Map<String, Object>> create(@RequestBody TaskRequest request) {
        return Result.ok(taskService.create(request));
    }

    /** 批量启停（需放在 /{id} 之前语义上更清晰，Spring 按字面量优先匹配）。 */
    @PostMapping("/batch-enable")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_BATCH_ENABLE", targetType = "TASK")
    public Result<Map<String, Object>> batchEnable(@RequestBody BatchEnableRequest request) {
        return Result.ok(taskService.batchEnable(request.getIds(), request.getEnabled()));
    }

    /** 任务详情（含映射/算子/依赖/版本/最近实例）。 */
    @GetMapping("/{id}")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(taskService.detail(id));
    }

    /** 修改任务。 */
    @PutMapping("/{id}")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_UPDATE", targetType = "TASK")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody TaskRequest request) {
        return Result.ok(taskService.update(id, request));
    }

    /** 逻辑删除任务。 */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_DELETE", targetType = "TASK")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Result.ok();
    }

    /** 启用任务。 */
    @PostMapping("/{id}/enable")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_ENABLE", targetType = "TASK")
    public Result<Map<String, Object>> enable(@PathVariable Long id) {
        return Result.ok(taskService.enable(id));
    }

    /** 停用任务。 */
    @PostMapping("/{id}/disable")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_DISABLE", targetType = "TASK")
    public Result<Map<String, Object>> disable(@PathVariable Long id) {
        return Result.ok(taskService.disable(id));
    }

    /** 手工触发：异步执行，返回 instanceId + batchId。 */
    @PostMapping("/{id}/run")
    @RequirePermission(PermissionConstants.TASK_RUN)
    @AuditLog(action = "TASK_TRIGGER", targetType = "TASK")
    public Result<RunTicket> run(@PathVariable Long id) {
        return Result.ok(instanceService.run(id, UserContext.userId(), UserContext.username()));
    }

    /** 终止当前实例。 */
    @PostMapping("/{id}/stop")
    @RequirePermission(PermissionConstants.TASK_RUN)
    @AuditLog(action = "TASK_STOP", targetType = "TASK")
    public Result<Map<String, Object>> stop(@PathVariable Long id) {
        return Result.ok(instanceService.stop(id));
    }

    /** 任务实例列表。 */
    @GetMapping("/{id}/instances")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<List<Map<String, Object>>> instances(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(required = false) String status) {
        Page<Map<String, Object>> page = instanceService.pageInstances(pageNum, pageSize, id, status, null, null, null, null);
        return Result.ok(page.getContent(), page.getTotalElements(), pageNum, pageSize);
    }

    /** 查询任务依赖（上游 + 下游）。 */
    @GetMapping("/{id}/deps")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> deps(@PathVariable Long id) {
        return Result.ok(taskDepService.dependencies(id));
    }

    /** 新增依赖（保存时做环检测）。 */
    @PostMapping("/{id}/deps")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_DEP_ADD", targetType = "TASK")
    public Result<Map<String, Object>> addDeps(@PathVariable Long id, @RequestBody DepRequest request) {
        return Result.ok(taskDepService.addDeps(id, request));
    }

    /** 删除依赖。 */
    @DeleteMapping("/{id}/deps/{upId}")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_DEP_DELETE", targetType = "TASK")
    public Result<Map<String, Object>> removeDep(@PathVariable Long id, @PathVariable Long upId) {
        return Result.ok(taskDepService.removeDep(id, upId));
    }

    /** 设置调度计划。 */
    @PostMapping("/{id}/schedule")
    @RequirePermission(PermissionConstants.TASK_WRITE)
    @AuditLog(action = "TASK_SCHEDULE", targetType = "TASK")
    public Result<Map<String, Object>> schedule(@PathVariable Long id, @RequestBody ScheduleRequest request) {
        return Result.ok(scheduleService.setSchedule(id, request));
    }
}
