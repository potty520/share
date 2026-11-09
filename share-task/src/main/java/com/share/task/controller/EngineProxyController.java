package com.share.task.controller;

import com.share.common.api.ErrorCode;
import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.exception.BizException;
import com.share.common.security.RequirePermission;
import com.share.task.engine.EngineProxyService;
import com.share.task.entity.SwBatch;
import com.share.task.entity.TkTask;
import com.share.task.repository.SwBatchRepository;
import com.share.task.repository.TkTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 外部执行引擎代理：SeaTunnel / Flink CDC 作业配置预览、提交与指标回采。 */
@RestController
@RequestMapping("/api/task/v1/engine/proxy")
@RequiredArgsConstructor
public class EngineProxyController {

    private final EngineProxyService engineProxyService;
    private final TkTaskRepository taskRepository;
    private final SwBatchRepository batchRepository;

    /** 预览任务对应的 SeaTunnel HOCON / Flink SQL。 */
    @GetMapping("/tasks/{taskId}/preview")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> preview(@PathVariable Long taskId,
                                               @RequestParam(required = false) String provider) {
        TkTask task = taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
        return Result.ok(engineProxyService.preview(task, provider));
    }

    /** 回采批次引擎指标。 */
    @GetMapping("/batches/{batchId}/metrics")
    @RequirePermission(PermissionConstants.TASK_READ)
    public Result<Map<String, Object>> metrics(@PathVariable String batchId) {
        SwBatch batch = batchRepository.findByBatchIdAndDeletedFalse(batchId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "批次不存在: " + batchId));
        return Result.ok(engineProxyService.collectMetrics(batch));
    }

    /** 为已触发批次补生成作业配置（运维排障用）。 */
    @PostMapping("/batches/{batchId}/attach")
    @RequirePermission(PermissionConstants.TASK_RUN)
    @AuditLog(action = "ENGINE_PROXY_ATTACH", targetType = "BATCH")
    public Result<Map<String, Object>> attach(@PathVariable String batchId) {
        SwBatch batch = batchRepository.findByBatchIdAndDeletedFalse(batchId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "批次不存在: " + batchId));
        TkTask task = taskRepository.findByIdAndDeletedFalse(batch.getTaskId())
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
        return Result.ok(engineProxyService.attachJob(task, batch));
    }
}
