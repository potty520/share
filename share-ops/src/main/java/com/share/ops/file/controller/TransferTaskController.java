package com.share.ops.file.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.ops.file.entity.FlTransferTask;
import com.share.ops.file.service.TransferService;
import com.share.ops.support.ResultSupport;
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
import java.util.Map;

/**
 * 文件收发任务接口（FR-09）：定时收发（cron）与目录监听任务管理，支持立即执行。
 */
@RestController
@RequestMapping("/api/file/v1/transfer-tasks")
@RequiredArgsConstructor
public class TransferTaskController {

    private final TransferService transferService;

    @GetMapping
    @RequirePermission(PermissionConstants.FILE_READ)
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Boolean enabled,
                                                  @RequestParam(required = false) String sourceType,
                                                  @RequestParam(defaultValue = "1") Integer pageNum,
                                                  @RequestParam(defaultValue = "10") Integer pageSize) {
        return ResultSupport.paged(transferService.page(keyword, enabled, sourceType, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    @RequirePermission(PermissionConstants.FILE_READ)
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(transferService.detail(id));
    }

    @PostMapping
    @RequirePermission(PermissionConstants.FILE_WRITE)
    @AuditLog(action = "FILE_TRANSFER_TASK_CREATE", targetType = "TRANSFER_TASK")
    public Result<Map<String, Object>> create(@RequestBody FlTransferTask request) {
        return Result.ok(transferService.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(PermissionConstants.FILE_WRITE)
    @AuditLog(action = "FILE_TRANSFER_TASK_UPDATE", targetType = "TRANSFER_TASK")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody FlTransferTask request) {
        return Result.ok(transferService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(PermissionConstants.FILE_WRITE)
    @AuditLog(action = "FILE_TRANSFER_TASK_DELETE", targetType = "TRANSFER_TASK")
    public Result<Void> delete(@PathVariable Long id) {
        transferService.delete(id);
        return Result.ok();
    }

    /** 立即执行：真实复制文件并回写传输日志 */
    @PostMapping("/{id}/run")
    @RequirePermission(PermissionConstants.FILE_WRITE)
    @AuditLog(action = "FILE_TRANSFER_RUN", targetType = "TRANSFER_TASK")
    public Result<Map<String, Object>> run(@PathVariable Long id) {
        return Result.ok(transferService.run(id));
    }
}
