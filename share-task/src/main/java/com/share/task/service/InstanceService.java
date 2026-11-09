package com.share.task.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.task.dto.RunTicket;
import com.share.task.engine.ExchangeEngine;
import com.share.task.entity.SwBatch;
import com.share.task.entity.SwTransferLog;
import com.share.task.entity.TkTask;
import com.share.task.entity.TkTaskInstance;
import com.share.task.repository.SwBatchRepository;
import com.share.task.repository.SwTransferLogRepository;
import com.share.task.repository.TkTaskInstanceRepository;
import com.share.task.repository.TkTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务实例服务（FR-04）：触发/终止/重跑/一致性核对、实例与日志查询、批次流转追溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TkTaskRepository taskRepository;

    private final TkTaskInstanceRepository instanceRepository;

    private final SwBatchRepository batchRepository;

    private final SwTransferLogRepository transferLogRepository;

    private final ExchangeEngine exchangeEngine;

    /** 手工触发任务。 */
    public RunTicket run(Long taskId, Long operatorId, String operatorName) {
        TkTask task = taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
        return exchangeEngine.trigger(task, "MANUAL", operatorId, operatorName);
    }

    /** 调度触发（供定时扫描器调用）。 */
    public RunTicket triggerByCron(TkTask task) {
        return exchangeEngine.trigger(task, "CRON", null, "scheduler");
    }

    /** 依赖触发（上游成功后触发下游任务）。 */
    public RunTicket triggerByDependency(TkTask task) {
        return exchangeEngine.trigger(task, "DEPENDENCY", null, "dependency-engine");
    }

    /** 终止任务当前运行实例。 */
    public Map<String, Object> stop(Long taskId) {
        return exchangeEngine.stop(taskId);
    }

    /** 重跑：基于历史实例所属任务创建新实例与新批次。 */
    public RunTicket rerun(Long instanceId, Long operatorId, String operatorName) {
        TkTaskInstance instance = requireInstance(instanceId);
        TkTask task = taskRepository.findByIdAndDeletedFalse(instance.getTaskId())
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND, "实例对应任务不存在"));
        return exchangeEngine.trigger(task, "RERUN", operatorId, operatorName);
    }

    /** 一致性核对。 */
    public Map<String, Object> verify(Long instanceId) {
        return exchangeEngine.verify(instanceId);
    }

    /** 全局实例分页列表。 */
    public Page<Map<String, Object>> pageInstances(int pageNum, int pageSize, Long taskId, String status,
                                                   String triggerType, String keyword, String from, String to) {
        List<TkTaskInstance> all = taskId == null
                ? instanceRepository.findByDeletedFalseOrderByIdDesc()
                : instanceRepository.findByTaskIdAndDeletedFalseOrderByIdDesc(taskId);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        List<TkTaskInstance> filtered = new ArrayList<>();
        for (TkTaskInstance instance : all) {
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(instance.getStatus())) {
                continue;
            }
            if (triggerType != null && !triggerType.isBlank() && !triggerType.equalsIgnoreCase(instance.getTriggerType())) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()) {
                String text = keyword.trim().toLowerCase();
                boolean match = contains(instance.getTaskName(), text) || contains(instance.getTaskCode(), text)
                        || contains(instance.getBatchId(), text);
                if (!match) {
                    continue;
                }
            }
            if (fromTime != null && instance.getStartTime() != null && instance.getStartTime().isBefore(fromTime)) {
                continue;
            }
            if (toTime != null && instance.getStartTime() != null && instance.getStartTime().isAfter(toTime)) {
                continue;
            }
            filtered.add(instance);
        }
        int normalizedPageNum = Math.max(1, pageNum);
        int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
        int fromIndex = Math.min((normalizedPageNum - 1) * normalizedPageSize, filtered.size());
        int toIndex = Math.min(fromIndex + normalizedPageSize, filtered.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (TkTaskInstance instance : filtered.subList(fromIndex, toIndex)) {
            Map<String, Object> map = BeanMaps.toMap(instance);
            batchRepository.findByBatchIdAndDeletedFalse(instance.getBatchId() == null ? "" : instance.getBatchId())
                    .ifPresent(batch -> {
                        map.put("sourceType", batch.getSourceType());
                        map.put("targetType", batch.getTargetType());
                        map.put("byteCount", batch.getByteCount());
                    });
            list.add(map);
        }
        return new PageImpl<>(list, PageRequest.of(normalizedPageNum - 1, normalizedPageSize), filtered.size());
    }

    /** 实例详情：实例 + 批次 + 最近日志。 */
    public Map<String, Object> detail(Long instanceId) {
        TkTaskInstance instance = requireInstance(instanceId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instance", BeanMaps.toMap(instance));
        SwBatch batch = instance.getBatchId() == null ? null
                : batchRepository.findByBatchIdAndDeletedFalse(instance.getBatchId()).orElse(null);
        detail.put("batch", batch == null ? null : BeanMaps.toMap(batch));
        List<SwTransferLog> logs = logsOf(instance);
        List<Map<String, Object>> logList = new ArrayList<>();
        int start = Math.max(0, logs.size() - 20);
        for (SwTransferLog transferLog : logs.subList(start, logs.size())) {
            logList.add(BeanMaps.toMap(transferLog));
        }
        detail.put("logs", logList);
        return detail;
    }

    /** 实例日志分页；tail=true 时返回尾部最新 N 条。 */
    public Page<Map<String, Object>> pageLogs(Long instanceId, int pageNum, int pageSize, boolean tail) {
        TkTaskInstance instance = requireInstance(instanceId);
        List<SwTransferLog> logs = logsOf(instance);
        int normalizedPageNum = Math.max(1, pageNum);
        int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
        int fromIndex;
        int toIndex;
        if (tail) {
            fromIndex = Math.max(0, logs.size() - normalizedPageSize);
            toIndex = logs.size();
        } else {
            fromIndex = Math.min((normalizedPageNum - 1) * normalizedPageSize, logs.size());
            toIndex = Math.min(fromIndex + normalizedPageSize, logs.size());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (SwTransferLog transferLog : logs.subList(fromIndex, toIndex)) {
            list.add(BeanMaps.toMap(transferLog));
        }
        return new PageImpl<>(list, PageRequest.of(normalizedPageNum - 1, normalizedPageSize), logs.size());
    }

    /** 批次查询：指定 batchId 返回批次 + 流转日志，否则返回最近批次列表。 */
    public Map<String, Object> batches(String batchId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<SwBatch> batches = new ArrayList<>();
        if (batchId != null && !batchId.isBlank()) {
            batchRepository.findByBatchIdAndDeletedFalse(batchId.trim()).ifPresent(batches::add);
            if (batches.isEmpty()) {
                throw new BizException(ErrorCode.NOT_FOUND, "批次不存在：" + batchId);
            }
        } else {
            batches.addAll(batchRepository.findTop20ByDeletedFalseOrderByIdDesc());
        }
        List<Map<String, Object>> batchMaps = new ArrayList<>();
        for (SwBatch batch : batches) {
            Map<String, Object> map = BeanMaps.toMap(batch);
            List<Map<String, Object>> logMaps = new ArrayList<>();
            for (SwTransferLog log : transferLogRepository.findByBatchIdAndDeletedFalseOrderByIdAsc(batch.getBatchId())) {
                logMaps.add(BeanMaps.toMap(log));
            }
            map.put("logs", logMaps);
            batchMaps.add(map);
        }
        result.put("batchId", batchId);
        result.put("batches", batchMaps);
        result.put("total", batchMaps.size());
        return result;
    }

    /** 实例对应日志（按批次号优先，兼容无批次的历史数据）。 */
    private List<SwTransferLog> logsOf(TkTaskInstance instance) {
        List<SwTransferLog> logs = instance.getBatchId() == null ? new ArrayList<>()
                : transferLogRepository.findByBatchIdAndDeletedFalseOrderByIdAsc(instance.getBatchId());
        if (logs.isEmpty()) {
            logs = transferLogRepository.findByInstanceIdAndDeletedFalseOrderByIdAsc(instance.getId());
        }
        return logs;
    }

    private TkTaskInstance requireInstance(Long instanceId) {
        return instanceRepository.findByIdAndDeletedFalse(instanceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务实例不存在"));
    }

    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            if (text.length() <= 10) {
                return LocalDateTime.parse(text + " 00:00:00", DATE_TIME);
            }
            return LocalDateTime.parse(text.replace('T', ' ').substring(0, 19), DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }
}
