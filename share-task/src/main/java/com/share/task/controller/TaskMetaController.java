package com.share.task.controller;

import com.share.common.api.Result;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.task.service.ProcessingService;
import com.share.task.service.ScheduleService;
import com.share.task.service.TaskService;
import com.share.task.dto.PreviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务辅助接口：调度计划列表、任务模板、加工算子字典、加工试运行（FR-04 / FR-05）。
 */
@RestController
@RequestMapping("/api/task/v1")
@RequiredArgsConstructor
public class TaskMetaController {

    private final ScheduleService scheduleService;

    private final TaskService taskService;

    private final ProcessingService processingService;

    /** 调度计划列表（含下次触发时间）。 */
    @GetMapping("/schedules")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<List<Map<String, Object>>> schedules() {
        return Result.ok(scheduleService.schedules());
    }

    /** 任务模板列表。 */
    @GetMapping("/templates")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<List<Map<String, Object>>> templates() {
        return Result.ok(taskService.templates());
    }

    /** 加工算子字典（算子 + 字段转换规则）。 */
    @GetMapping("/operators")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> operators() {
        return Result.ok(processingService.dictionary());
    }

    /** 加工试运行（纯内存计算）。 */
    @PostMapping("/preview")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> preview(@RequestBody PreviewRequest request) {
        return Result.ok(processingService.preview(request));
    }
}
