package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.event.ExchangeEvent;
import com.share.common.event.NotifyEvent;
import com.share.common.exception.BizException;
import com.share.common.util.IdGen;
import com.share.common.util.JsonUtil;
import com.share.common.web.TraceTagService;
import com.share.task.dto.MappingRule;
import com.share.task.dto.RunTicket;
import com.share.task.dto.TransformResult;
import com.share.task.entity.SwBatch;
import com.share.task.entity.SwCheckpoint;
import com.share.task.entity.SwTransferLog;
import com.share.task.entity.TkTask;
import com.share.task.entity.TkTaskInstance;
import com.share.task.repository.SwBatchRepository;
import com.share.task.repository.SwCheckpointRepository;
import com.share.task.repository.SwTransferLogRepository;
import com.share.task.repository.TkTaskInstanceRepository;
import com.share.task.repository.TkTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 真实数据交换引擎（FR-02/04/05，模拟 SeaTunnel 但数据真实搬运）：
 * <ol>
 *   <li>异步执行：每次触发生成 instanceId + batchId，提交到独立线程池；</li>
 *   <li>读取源端（内置表/外部 JDBC 表/CSV）→ 字段映射 → 算子链 → 静态脱敏 → 写入目标（内置表/外部 JDBC 表/CSV）；</li>
 *   <li>批次落 sw_batch、阶段落 sw_transfer_log、断点落 sw_checkpoint，进度按 5% 推进；</li>
 *   <li>失败按 share.engine.retry-times 指数退避重试；</li>
 *   <li>幂等：同一 batchId 目标端先删后写，批次已成功则跳过重复写入；</li>
 *   <li>完成后发布 ExchangeEvent（监控/审计/流转追溯），失败额外发布 NotifyEvent。</li>
 * </ol>
 */
@Slf4j
@Service
public class ExchangeEngine {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_STOPPED = "STOPPED";

    private final TkTaskRepository taskRepository;
    private final TkTaskInstanceRepository instanceRepository;
    private final SwBatchRepository batchRepository;
    private final SwTransferLogRepository transferLogRepository;
    private final SwCheckpointRepository checkpointRepository;
    private final SourceReader sourceReader;
    private final TargetWriter targetWriter;
    private final JdbcConnectionFactory jdbcConnectionFactory;
    private final TransformEngine transformEngine;
    private final RunningTaskRegistry runningTaskRegistry;
    private final EngineSettings settings;
    private final EngineProxyService engineProxyService;
    private final TraceTagService traceTagService;
    private final ApplicationEventPublisher publisher;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor;

    public ExchangeEngine(TkTaskRepository taskRepository,
                          TkTaskInstanceRepository instanceRepository,
                          SwBatchRepository batchRepository,
                          SwTransferLogRepository transferLogRepository,
                          SwCheckpointRepository checkpointRepository,
                          SourceReader sourceReader,
                          TargetWriter targetWriter,
                          JdbcConnectionFactory jdbcConnectionFactory,
                          TransformEngine transformEngine,
                          RunningTaskRegistry runningTaskRegistry,
                          EngineSettings settings,
                          EngineProxyService engineProxyService,
                          TraceTagService traceTagService,
                          ApplicationEventPublisher publisher,
                          JdbcTemplate jdbcTemplate,
                          @Qualifier("shareEngineExecutor") ExecutorService executor) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.batchRepository = batchRepository;
        this.transferLogRepository = transferLogRepository;
        this.checkpointRepository = checkpointRepository;
        this.sourceReader = sourceReader;
        this.targetWriter = targetWriter;
        this.jdbcConnectionFactory = jdbcConnectionFactory;
        this.transformEngine = transformEngine;
        this.runningTaskRegistry = runningTaskRegistry;
        this.settings = settings;
        this.engineProxyService = engineProxyService;
        this.traceTagService = traceTagService;
        this.publisher = publisher;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
    }

    /**
     * 触发任务执行（手工/调度/依赖/重跑共用）：同步创建实例与批次记录，异步执行搬运。
     */
    public RunTicket trigger(TkTask task, String triggerType, Long operatorId, String operatorName) {
        if (runningTaskRegistry.isRunning(task.getId())) {
            throw new BizException(ErrorCode.TASK_RUNNING,
                    "任务【" + task.getTaskName() + "】正在运行，请勿重复触发");
        }
        String batchId = IdGen.batchId();
        traceTagService.tag(TraceTagService.BATCH_ID, batchId);
        LocalDateTime now = LocalDateTime.now();

        TkTaskInstance instance = new TkTaskInstance();
        instance.setTaskId(task.getId());
        instance.setTaskCode(task.getTaskCode());
        instance.setTaskName(task.getTaskName());
        instance.setBatchId(batchId);
        instance.setTriggerType(triggerType);
        instance.setStatus(STATUS_RUNNING);
        instance.setProgress(0);
        instance.setReadRows(0L);
        instance.setTotalRows(0L);
        instance.setWriteRows(0L);
        instance.setFailedRows(0L);
        instance.setVerifyStatus("NOT_VERIFIED");
        instance.setStartTime(now);
        instance.setOperatorId(operatorId);
        instance.setOperatorName(operatorName);
        instance.setDeptId(task.getDeptId());
        instance = instanceRepository.save(instance);

        SwBatch batch = new SwBatch();
        batch.setBatchId(batchId);
        batch.setTaskId(task.getId());
        batch.setTaskName(task.getTaskName());
        batch.setTaskType(task.getTaskType());
        batch.setSourceType(SourceReader.resolveSourceType(task));
        batch.setTargetType(SourceReader.resolveTargetType(task));
        batch.setSourceId(task.getSourceId());
        batch.setTargetId(task.getTargetId());
        batch.setTargetTable("CSV_FILE".equals(SourceReader.resolveTargetType(task))
                ? TargetWriter.resolveTargetPath(task).toString() : task.getTargetTable());
        batch.setStatus(STATUS_RUNNING);
        batch.setReadRows(0L);
        batch.setSuccessRows(0L);
        batch.setFailedRows(0L);
        batch.setByteCount(0L);
        batch.setStartTime(now);
        batch.setOperatorId(operatorId);
        batch.setDeptId(task.getDeptId());
        batchRepository.save(batch);
        engineProxyService.attachJob(task, batch);

        runningTaskRegistry.register(task.getId(), instance.getId());

        task.setStatus(STATUS_RUNNING);
        task.setLastRunTime(now);
        task.setLastBatchId(batchId);
        taskRepository.save(task);

        appendLog(task.getId(), instance.getId(), batchId, "READ", "INFO",
                "任务已提交交换引擎，批次号 " + batchId + "，触发方式 " + triggerType, 0L, null, null);

        Long instanceId = instance.getId();
        Long taskId = task.getId();
        try {
            executor.submit(() -> executeWithRetry(taskId, instanceId, batchId));
        } catch (RuntimeException e) {
            runningTaskRegistry.unregister(taskId, instanceId);
            markFailed(taskId, instanceId, batchId, "提交执行线程失败：" + e.getMessage(), 0L);
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "提交执行线程失败：" + e.getMessage());
        }
        return new RunTicket(taskId, task.getTaskName(), instanceId, batchId, STATUS_RUNNING);
    }

    /** 指数退避重试执行。 */
    private void executeWithRetry(Long taskId, Long instanceId, String batchId) {
        traceTagService.beginBatch(batchId);
        int maxRetry = Math.max(0, settings.getRetryTimes());
        try {
            for (int attempt = 0; attempt <= maxRetry; attempt++) {
                try {
                    TkTask task = taskRepository.findByIdAndDeletedFalse(taskId)
                            .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
                    doExecute(task, instanceId, batchId, attempt);
                    return;
                } catch (StopRequestedException e) {
                    markStopped(taskId, instanceId, batchId, e.getMessage());
                    return;
                } catch (Exception e) {
                    log.warn("交换任务执行失败 taskId={} attempt={}/{} msg={}", taskId, attempt + 1, maxRetry + 1, e.getMessage());
                    if (attempt >= maxRetry) {
                        markFailed(taskId, instanceId, batchId, e.getMessage(), 0L);
                        return;
                    }
                    long waitMillis = Math.max(1, settings.getRetryIntervalSeconds()) * 1000L * (1L << attempt);
                    appendLog(taskId, instanceId, batchId, "RETRY", "WARN",
                            "第 " + (attempt + 1) + " 次执行失败：" + e.getMessage() + "，" + (waitMillis / 1000) + " 秒后重试",
                            0L, null, null);
                    updateInstanceError(instanceId, "第 " + (attempt + 1) + " 次执行失败：" + e.getMessage());
                    try {
                        Thread.sleep(waitMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        markStopped(taskId, instanceId, batchId, "执行线程被中断");
                        return;
                    }
                }
            }
        } catch (Throwable fatal) {
            log.error("交换引擎未捕获异常 taskId={}", taskId, fatal);
            markFailed(taskId, instanceId, batchId, "引擎内部异常：" + fatal.getMessage(), 0L);
        } finally {
            runningTaskRegistry.unregister(taskId, instanceId);
            traceTagService.endBatch();
        }
    }

    /** 单次执行主流程。 */
    private void doExecute(TkTask task, Long instanceId, String batchId, int attempt) {
        TkTaskInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "实例不存在"));
        SwBatch batch = batchRepository.findByBatchIdAndDeletedFalse(batchId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "批次不存在"));
        runningTaskRegistry.checkCancelled(instanceId);

        // 幂等：批次已成功则不再重复写入
        if (STATUS_SUCCESS.equals(batch.getStatus())) {
            appendLog(task.getId(), instanceId, batchId, "FINISH", "INFO",
                    "幂等命中：批次 " + batchId + " 已成功写入，跳过重复执行", batch.getSuccessRows(), null, null);
            markSuccess(instance, task, batch, batch.getSuccessRows(), batch.getChecksum(), batch.getByteCount());
            return;
        }

        long totalStart = System.currentTimeMillis();

        // 1. 读取源端
        long readStart = System.currentTimeMillis();
        SourceReader.SourceData sourceData = sourceReader.read(task);
        long readCost = System.currentTimeMillis() - readStart;
        updateProgress(instance, 10);
        appendLog(task.getId(), instanceId, batchId, "READ", "INFO",
                "读取源端完成：" + sourceData.getSourceType() + " / " + sourceData.getSourceRef()
                        + "，行数 " + sourceData.getRows().size(),
                (long) sourceData.getRows().size(), readCost, sourceData.getConnectionInfo());
        runningTaskRegistry.checkCancelled(instanceId);

        // 2. 字段映射 + 算子链 + 静态脱敏
        long transformStart = System.currentTimeMillis();
        List<MappingRule> mapping = transformEngine.parseMapping(task.getMappingJson());
        List<Map<String, Object>> operators = transformEngine.parseOperators(task.getOperatorsJson());
        TransformResult transform = transformEngine.transform(sourceData.getRows(), mapping, operators, task.getFilterCondition());
        long transformCost = System.currentTimeMillis() - transformStart;
        updateProgress(instance, 35);
        appendLog(task.getId(), instanceId, batchId, "TRANSFORM", "INFO",
                "字段映射/算子链处理完成：映射 " + mapping.size() + " 条、算子 " + operators.size()
                        + " 个，输出 " + transform.getRows().size() + " 行，过滤/清洗 "
                        + transform.getFilteredRows() + " 行",
                (long) transform.getRows().size(), transformCost,
                JsonUtil.toJson(transform.getOperatorStats()));
        int maskFields = countMaskFields(mapping);
        if (maskFields > 0) {
            appendLog(task.getId(), instanceId, batchId, "MASK", "INFO",
                    "静态脱敏完成：按映射规则脱敏 " + maskFields + " 个敏感字段", (long) transform.getRows().size(), null, null);
        }
        runningTaskRegistry.checkCancelled(instanceId);

        // 3. 写入目标端（写入过程按 5% 更新进度，并检查终止信号）
        long writeStart = System.currentTimeMillis();
        int total = transform.getRows().size();
        int[] lastReported = {40};
        TargetWriter.WriteResult writeResult = targetWriter.write(task, transform.getColumns(), transform.getRows(), batchId,
                written -> onWriteProgress(instance, written, total, lastReported));
        long writeCost = System.currentTimeMillis() - writeStart;
        appendLog(task.getId(), instanceId, batchId, "WRITE", "INFO",
                "写入目标端完成：" + writeResult.getTargetRef() + "，行数 " + writeResult.getWrittenRows(),
                writeResult.getWrittenRows(), writeCost, null);
        runningTaskRegistry.checkCancelled(instanceId);

        // 4. 校验值 + 断点 + 收尾
        String checksum = RowChecksum.of(transform.getColumns(), transform.getRows());
        long bytes = writeResult.getBytes() > 0 ? writeResult.getBytes()
                : RowChecksum.bytesOf(transform.getColumns(), transform.getRows());
        updateProgress(instance, 95);
        appendLog(task.getId(), instanceId, batchId, "VERIFY", "INFO",
                "生成数据校验值：" + checksum + "，写入字节数约 " + bytes, (long) total, null, null);
        saveCheckpoint(task.getId(), batchId, total, transform.getColumns().size());
        if ("INCREMENT".equalsIgnoreCase(task.getTaskType()) && sourceData.getWatermark() != null
                && !sourceData.getWatermark().isBlank()) {
            task.setLastWatermark(sourceData.getWatermark());
            taskRepository.save(task);
            appendLog(task.getId(), instanceId, batchId, "CHECKPOINT", "INFO",
                    "增量水位已推进至 " + sourceData.getWatermark(), (long) total, null, null);
        }
        long totalCost = System.currentTimeMillis() - totalStart;
        instance.setReadRows((long) sourceData.getRows().size());
        markSuccess(instance, task, batch, writeResult.getWrittenRows(), checksum, bytes);
        batch.setCostMs(totalCost);
        batch.setEndTime(LocalDateTime.now());
        batch.setReadRows((long) sourceData.getRows().size());
        batch.setTotalRows((long) total);
        batch.setFailedRows((long) Math.max(0, sourceData.getRows().size() - total));
        batch.setColumnsJson(JsonUtil.toJson(transform.getColumns()));
        batchRepository.save(batch);
        appendLog(task.getId(), instanceId, batchId, "FINISH", "INFO",
                "批次 " + batchId + " 执行成功，重试次数 " + attempt, (long) total, totalCost, null);
        publishSuccess(task, batch, instance, totalCost);
    }

    /** 写入进度回调：按 5% 粒度落库并检查终止标记。 */
    private void onWriteProgress(TkTaskInstance instance, long written, int total, int[] lastReported) {
        int percent = total <= 0 ? 90 : 40 + (int) (written * 50 / Math.max(1, total));
        percent = Math.min(90, percent);
        if (percent >= lastReported[0] + 5 || written >= total) {
            instance.setProgress(percent);
            instance.setWriteRows(written);
            instanceRepository.save(instance);
            lastReported[0] = percent;
        }
        runningTaskRegistry.checkCancelled(instance.getId());
    }

    /** 标记实例与批次成功，更新任务状态，发布成功事件。 */
    private void markSuccess(TkTaskInstance instance, TkTask task, SwBatch batch,
                             long writtenRows, String checksum, long bytes) {
        LocalDateTime end = LocalDateTime.now();
        long cost = instance.getStartTime() == null ? 0 : java.time.Duration.between(instance.getStartTime(), end).toMillis();
        instance.setStatus(STATUS_SUCCESS);
        instance.setProgress(100);
        instance.setWriteRows(writtenRows);
        instance.setTotalRows(writtenRows);
        instance.setChecksum(checksum);
        instance.setEndTime(end);
        instance.setCostMs(cost);
        instance.setErrorMsg(null);
        instanceRepository.save(instance);

        if (!STATUS_SUCCESS.equals(batch.getStatus())) {
            batch.setStatus(STATUS_SUCCESS);
            batch.setSuccessRows(writtenRows);
            batch.setChecksum(checksum);
            batch.setByteCount(bytes);
            batch.setEndTime(end);
            batchRepository.save(batch);
        }
        task.setStatus("READY");
        task.setLastRunTime(end);
        taskRepository.save(task);
    }

    /** 失败处理：实例/批次置 FAILED，任务置 FAILED，发布失败事件与通知。 */
    private void markFailed(Long taskId, Long instanceId, String batchId, String message, long costMs) {
        LocalDateTime end = LocalDateTime.now();
        TkTaskInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId).orElse(null);
        if (instance != null) {
            instance.setStatus(STATUS_FAILED);
            instance.setEndTime(end);
            instance.setErrorMsg(truncate(message, 1000));
            instance.setCostMs(costMs > 0 ? costMs : costOf(instance));
            instanceRepository.save(instance);
        }
        SwBatch batch = batchRepository.findByBatchIdAndDeletedFalse(batchId).orElse(null);
        if (batch != null) {
            batch.setStatus(STATUS_FAILED);
            batch.setEndTime(end);
            batch.setErrorMsg(truncate(message, 1000));
            batch.setCostMs(costMs > 0 ? costMs : costOf(instance));
            batchRepository.save(batch);
        }
        taskRepository.findByIdAndDeletedFalse(taskId).ifPresent(task -> {
            task.setStatus(STATUS_FAILED);
            taskRepository.save(task);
        });
        appendLog(taskId, instanceId, batchId, "FINISH", "ERROR", "批次执行失败：" + message, 0L, null, null);
        publishFailure(taskId, batch, message);
        publishNotify("交换任务执行失败", "批次 " + batchId + " 执行失败：" + message, "ERROR");
    }

    /** 终止处理：实例/批次置 STOPPED，任务回到 READY。 */
    private void markStopped(Long taskId, Long instanceId, String batchId, String message) {
        LocalDateTime end = LocalDateTime.now();
        TkTaskInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId).orElse(null);
        if (instance != null) {
            instance.setStatus(STATUS_STOPPED);
            instance.setEndTime(end);
            instance.setErrorMsg(truncate(message, 1000));
            instance.setCostMs(costOf(instance));
            instanceRepository.save(instance);
        }
        SwBatch batch = batchRepository.findByBatchIdAndDeletedFalse(batchId).orElse(null);
        if (batch != null) {
            batch.setStatus(STATUS_STOPPED);
            batch.setEndTime(end);
            batch.setErrorMsg(truncate(message, 1000));
            batchRepository.save(batch);
        }
        taskRepository.findByIdAndDeletedFalse(taskId).ifPresent(task -> {
            task.setStatus("READY");
            taskRepository.save(task);
        });
        appendLog(taskId, instanceId, batchId, "FINISH", "WARN", "批次 " + batchId + " 被终止：" + message, 0L, null, null);
        publishNotify("交换任务被终止", "批次 " + batchId + " 已终止：" + message, "WARN");
    }

    /** 手工终止任务当前实例。 */
    public Map<String, Object> stop(Long taskId) {
        Long instanceId = runningTaskRegistry.runningInstanceId(taskId);
        if (instanceId == null) {
            throw new BizException(ErrorCode.TASK_STATUS_ILLEGAL, "任务当前没有运行中的实例");
        }
        boolean accepted = runningTaskRegistry.cancel(instanceId);
        TkTaskInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId).orElse(null);
        String batchId = instance == null ? null : instance.getBatchId();
        appendLog(taskId, instanceId, batchId, "STOP", "WARN", "收到手工终止请求，将在当前批次写入完成后停止", 0L, null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("instanceId", instanceId);
        result.put("batchId", batchId);
        result.put("stopRequested", accepted);
        return result;
    }

    /**
     * 一致性核对（FR-04）：重新统计目标端行数并复算 MD5，与 sw_batch 记录比对。
     */
    public Map<String, Object> verify(Long instanceId) {
        TkTaskInstance instance = instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "实例不存在"));
        SwBatch batch = batchRepository.findByBatchIdAndDeletedFalse(instance.getBatchId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "实例对应的批次记录不存在"));
        List<String> columns;
        try {
            columns = JsonUtil.fromJsonList(batch.getColumnsJson(), String.class);
        } catch (Exception e) {
            columns = new ArrayList<>();
        }
        long actualRows = 0;
        String actualChecksum = null;
        String reason = null;
        boolean success = false;
        try {
            if ("CSV_FILE".equalsIgnoreCase(batch.getTargetType())) {
                Path path = batch.getTargetTable() == null ? null : java.nio.file.Paths.get(batch.getTargetTable());
                if (path == null || !Files.exists(path)) {
                    reason = "目标文件不存在：" + batch.getTargetTable();
                } else {
                    List<Map<String, Object>> rows = CsvUtil.read(path);
                    actualRows = rows.size();
                    actualChecksum = RowChecksum.of(columns, rows);
                    success = true;
                }
            } else if ("JDBC_TABLE".equalsIgnoreCase(batch.getTargetType())) {
                String table = batch.getTargetTable();
                SourceReader.validateIdentifier(table, "目标表名");
                JdbcConnectionFactory.JdbcEndpoint endpoint =
                        jdbcConnectionFactory.resolve(batch.getTargetId(), "目标端");
                Long counted = endpoint.jdbc().queryForObject(
                        "select count(*) from " + table + " where " + TargetWriter.BATCH_COLUMN + " = ?",
                        Long.class, batch.getBatchId());
                actualRows = counted == null ? 0 : counted;
                List<Map<String, Object>> rows = endpoint.jdbc().queryForList(
                        "select * from " + table + " where " + TargetWriter.BATCH_COLUMN + " = ? order by "
                                + TargetWriter.ROW_COLUMN, batch.getBatchId());
                actualChecksum = RowChecksum.of(columns, rows);
                success = true;
            } else {
                String table = batch.getTargetTable();
                if (table == null || !targetWriter.tableExists(table)) {
                    reason = "目标表不存在：" + table;
                } else {
                    Long counted = jdbcTemplate.queryForObject(
                            "select count(*) from " + table + " where " + TargetWriter.BATCH_COLUMN + " = ?",
                            Long.class, batch.getBatchId());
                    actualRows = counted == null ? 0 : counted;
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                            "select * from " + table + " where " + TargetWriter.BATCH_COLUMN + " = ? order by "
                                    + TargetWriter.ROW_COLUMN, batch.getBatchId());
                    actualChecksum = RowChecksum.of(columns, rows);
                    success = true;
                }
            }
        } catch (Exception e) {
            reason = "核对失败：" + e.getMessage();
        }
        long expectedRows = batch.getSuccessRows() == null ? 0 : batch.getSuccessRows();
        boolean rowMatch = actualRows == expectedRows;
        boolean checksumMatch = batch.getChecksum() != null && batch.getChecksum().equalsIgnoreCase(actualChecksum);
        String verifyStatus = (success && rowMatch && checksumMatch) ? "PASS" : "FAIL";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchId", batch.getBatchId());
        detail.put("target", batch.getTargetTable());
        detail.put("expectedRows", expectedRows);
        detail.put("actualRows", actualRows);
        detail.put("expectedChecksum", batch.getChecksum());
        detail.put("actualChecksum", actualChecksum);
        detail.put("rowMatch", rowMatch);
        detail.put("checksumMatch", checksumMatch);
        detail.put("verifyStatus", verifyStatus);
        detail.put("reason", reason);
        detail.put("verifyTime", LocalDateTime.now().toString());
        instance.setVerifyStatus(verifyStatus);
        instance.setVerifyResult(truncate(JsonUtil.toJson(detail), 2000));
        instanceRepository.save(instance);
        appendLog(instance.getTaskId(), instanceId, batch.getBatchId(), "VERIFY",
                "PASS".equals(verifyStatus) ? "INFO" : "WARN",
                "一致性核对 " + verifyStatus + "：行数 " + expectedRows + " / " + actualRows
                        + "，校验值一致=" + checksumMatch,
                actualRows, null, JsonUtil.toJson(detail));
        Map<String, Object> result = new LinkedHashMap<>(detail);
        result.put("instanceId", instanceId);
        result.put("taskId", instance.getTaskId());
        return result;
    }

    /** 查询实例运行日志（供接口复用）。 */
    public List<SwTransferLog> logs(String batchId) {
        return transferLogRepository.findByBatchIdAndDeletedFalseOrderByIdAsc(batchId);
    }

    // ====================== 内部辅助 ======================

    private void updateProgress(TkTaskInstance instance, int progress) {
        instance.setProgress(progress);
        instanceRepository.save(instance);
    }

    private void updateInstanceError(Long instanceId, String message) {
        instanceRepository.findByIdAndDeletedFalse(instanceId).ifPresent(instance -> {
            instance.setErrorMsg(truncate(message, 1000));
            instanceRepository.save(instance);
        });
    }

    private long costOf(TkTaskInstance instance) {
        if (instance == null || instance.getStartTime() == null) {
            return 0L;
        }
        return java.time.Duration.between(instance.getStartTime(), LocalDateTime.now()).toMillis();
    }

    private int countMaskFields(List<MappingRule> mapping) {
        int count = 0;
        for (MappingRule rule : mapping) {
            if (rule.getMaskType() != null && !rule.getMaskType().isBlank()
                    && !"NONE".equalsIgnoreCase(rule.getMaskType().trim())) {
                count++;
            }
            String transform = rule.getTransform();
            if (transform != null && (transform.toUpperCase().startsWith("MASK_"))) {
                count++;
            }
        }
        return count;
    }

    private void saveCheckpoint(Long taskId, String batchId, int totalRows, int columns) {
        SwCheckpoint checkpoint = new SwCheckpoint();
        checkpoint.setTaskId(taskId);
        checkpoint.setBatchId(batchId);
        int chunkSize = Math.max(1, settings.getBatchSize());
        checkpoint.setChunkNo((totalRows + chunkSize - 1) / chunkSize);
        checkpoint.setOffsetRow((long) totalRows);
        checkpoint.setTotalRows((long) totalRows);
        checkpoint.setState("DONE");
        checkpoint.setCheckpointTime(LocalDateTime.now());
        checkpoint.setRemark("数据列数 " + columns + "，批次写入完成");
        checkpointRepository.save(checkpoint);
    }

    private void appendLog(Long taskId, Long instanceId, String batchId, String stage, String level,
                           String message, Long rows, Long costMs, String detailJson) {
        try {
            SwTransferLog transferLog = new SwTransferLog();
            transferLog.setTaskId(taskId);
            transferLog.setInstanceId(instanceId);
            transferLog.setBatchId(batchId);
            transferLog.setStage(stage);
            transferLog.setLevel(level);
            transferLog.setMessage(truncate(message, 2000));
            transferLog.setRowsCount(rows);
            transferLog.setCostMs(costMs);
            transferLog.setLogTime(LocalDateTime.now());
            transferLog.setDetailJson(truncate(detailJson, 4000));
            transferLogRepository.save(transferLog);
        } catch (Exception e) {
            log.warn("写入交换流转日志失败：{}", e.getMessage());
        }
    }

    /** 发布成功事件（供监控/审计/流转追溯消费）。 */
    private void publishSuccess(TkTask task, SwBatch batch, TkTaskInstance instance, long costMs) {
        try {
            publisher.publishEvent(ExchangeEvent.builder()
                    .batchId(batch.getBatchId())
                    .taskId(task.getId())
                    .taskName(task.getTaskName())
                    .taskType(task.getTaskType())
                    .sourceType(batch.getSourceType())
                    .targetType(batch.getTargetType())
                    .sourceId(task.getSourceId())
                    .rowCount(batch.getSuccessRows() == null ? 0 : batch.getSuccessRows())
                    .byteCount(batch.getByteCount() == null ? 0 : batch.getByteCount())
                    .success(true)
                    .costMs(costMs)
                    .checksum(batch.getChecksum())
                    .operatorId(instance.getOperatorId())
                    .deptId(task.getDeptId())
                    .startTime(batch.getStartTime())
                    .endTime(batch.getEndTime())
                    .build());
        } catch (Exception e) {
            log.warn("发布 ExchangeEvent 失败：{}", e.getMessage());
        }
        publishNotify("交换任务执行成功",
                "任务【" + task.getTaskName() + "】批次 " + batch.getBatchId() + " 执行成功，写入 "
                        + batch.getSuccessRows() + " 行", "INFO");
    }

    /** 发布失败事件。 */
    private void publishFailure(Long taskId, SwBatch batch, String message) {
        try {
            publisher.publishEvent(ExchangeEvent.builder()
                    .batchId(batch == null ? null : batch.getBatchId())
                    .taskId(taskId)
                    .taskName(batch == null ? null : batch.getTaskName())
                    .taskType(batch == null ? null : batch.getTaskType())
                    .sourceType(batch == null ? null : batch.getSourceType())
                    .targetType(batch == null ? null : batch.getTargetType())
                    .sourceId(batch == null ? null : batch.getSourceId())
                    .rowCount(batch == null || batch.getSuccessRows() == null ? 0 : batch.getSuccessRows())
                    .success(false)
                    .errorMsg(truncate(message, 1000))
                    .startTime(batch == null ? null : batch.getStartTime())
                    .endTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("发布 ExchangeEvent 失败：{}", e.getMessage());
        }
    }

    /** 发布通知事件（share-ops 消费为站内消息/告警）。 */
    private void publishNotify(String title, String content, String level) {
        try {
            publisher.publishEvent(NotifyEvent.builder()
                    .title(title)
                    .content(content)
                    .bizType("TASK")
                    .channel("INNER")
                    .level(level)
                    .link("/task/instances")
                    .build());
        } catch (Exception e) {
            log.warn("发布 NotifyEvent 失败：{}", e.getMessage());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
