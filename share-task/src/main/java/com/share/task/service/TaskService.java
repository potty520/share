package com.share.task.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.JsonUtil;
import com.share.task.dto.TaskRequest;
import com.share.task.entity.TkTask;
import com.share.task.entity.TkTaskInstance;
import com.share.task.entity.TkTaskVersion;
import com.share.task.entity.TkTemplate;
import com.share.task.engine.RunningTaskRegistry;
import com.share.task.engine.SourceReader;
import com.share.task.engine.TransformEngine;
import com.share.task.repository.TkTaskInstanceRepository;
import com.share.task.repository.TkTaskRepository;
import com.share.task.repository.TkTaskVersionRepository;
import com.share.task.repository.TkTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 交换任务管理服务（FR-04）：任务 CRUD、版本快照、详情聚合、启停、模板。
 * <p>任务定义中的映射规则与算子链以 JSON 持久化（mapping_json / operators_json），
 * 每次变更追加 tk_task_version 快照，满足“加工规则版本管理”要求。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TkTaskRepository taskRepository;

    private final TkTaskVersionRepository versionRepository;

    private final TkTaskInstanceRepository instanceRepository;

    private final TkTemplateRepository templateRepository;

    private final TaskDepService taskDepService;

    private final TransformEngine transformEngine;

    private final MetadataReadService metadataReadService;

    private final RunningTaskRegistry runningTaskRegistry;

    /** 任务分页列表。 */
    public Page<Map<String, Object>> page(int pageNum, int pageSize, String keyword, String taskType,
                                          String status, Boolean enabled, Long deptId) {
        List<TkTask> all = taskRepository.findByDeletedFalseOrderByIdDesc();
        List<TkTask> filtered = new ArrayList<>();
        for (TkTask task : all) {
            if (keyword != null && !keyword.isBlank()) {
                String text = keyword.trim().toLowerCase();
                boolean match = contains(task.getTaskName(), text) || contains(task.getTaskCode(), text)
                        || contains(task.getRemark(), text);
                if (!match) {
                    continue;
                }
            }
            if (taskType != null && !taskType.isBlank() && !taskType.equalsIgnoreCase(task.getTaskType())) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(task.getStatus())) {
                continue;
            }
            if (enabled != null && !enabled.equals(task.getEnabled())) {
                continue;
            }
            if (deptId != null && !deptId.equals(task.getDeptId())) {
                continue;
            }
            filtered.add(task);
        }
        int normalizedPageNum = Math.max(1, pageNum);
        int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
        int from = Math.min((normalizedPageNum - 1) * normalizedPageSize, filtered.size());
        int to = Math.min(from + normalizedPageSize, filtered.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (TkTask task : filtered.subList(from, to)) {
            list.add(toSummary(task));
        }
        return new PageImpl<>(list, PageRequest.of(normalizedPageNum - 1, normalizedPageSize), filtered.size());
    }

    /** 新建任务。 */
    @Transactional
    public Map<String, Object> create(TaskRequest request) {
        if (request.getTaskCode() == null || request.getTaskCode().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "taskCode 不能为空");
        }
        if (request.getTaskName() == null || request.getTaskName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "taskName 不能为空");
        }
        if (taskRepository.existsByTaskCodeAndDeletedFalse(request.getTaskCode().trim())) {
            throw new BizException(ErrorCode.CONFLICT, "任务编码已存在：" + request.getTaskCode());
        }
        TkTask task = new TkTask();
        applyRequest(task, request);
        validateDefinition(task);
        task.setStatus(request.getStatus() == null || request.getStatus().isBlank() ? "DRAFT" : request.getStatus());
        if (task.getEnabled() == null) {
            task.setEnabled(Boolean.TRUE);
        }
        task = taskRepository.save(task);
        saveVersion(task, "CREATE", request.getChangeNote());
        log.info("创建交换任务 id={} code={}", task.getId(), task.getTaskCode());
        return detail(task.getId());
    }

    /** 修改任务。 */
    @Transactional
    public Map<String, Object> update(Long id, TaskRequest request) {
        TkTask task = requireTask(id);
        if (runningTaskRegistry.isRunning(id)) {
            throw new BizException(ErrorCode.TASK_RUNNING, "任务运行中，不能修改定义");
        }
        if (request.getTaskCode() != null && !request.getTaskCode().isBlank()
                && !request.getTaskCode().trim().equals(task.getTaskCode())
                && taskRepository.existsByTaskCodeAndDeletedFalse(request.getTaskCode().trim())) {
            throw new BizException(ErrorCode.CONFLICT, "任务编码已存在：" + request.getTaskCode());
        }
        applyRequest(task, request);
        validateDefinition(task);
        task = taskRepository.save(task);
        saveVersion(task, "UPDATE", request.getChangeNote());
        return detail(task.getId());
    }

    /** 逻辑删除任务（同时清理依赖边）。 */
    @Transactional
    public void delete(Long id) {
        TkTask task = requireTask(id);
        if (runningTaskRegistry.isRunning(id)) {
            throw new BizException(ErrorCode.TASK_RUNNING, "任务运行中，不能删除");
        }
        task.setDeleted(true);
        taskRepository.save(task);
        taskDepService.deleteAllOfTask(id);
        log.info("逻辑删除交换任务 id={}", id);
    }

    /** 任务详情：任务定义 + 映射 + 算子 + 依赖 + 版本 + 最近实例 + 跨域元数据。 */
    public Map<String, Object> detail(Long id) {
        TkTask task = requireTask(id);
        Map<String, Object> detail = BeanMaps.toMap(task);
        detail.put("mapping", transformEngine.parseMapping(task.getMappingJson()));
        detail.put("operators", transformEngine.parseOperators(task.getOperatorsJson()));
        detail.put("dependencies", taskDepService.dependencies(id));
        List<Map<String, Object>> recentInstances = new ArrayList<>();
        for (TkTaskInstance instance : instanceRepository.findTop5ByTaskIdAndDeletedFalseOrderByIdDesc(id)) {
            recentInstances.add(BeanMaps.toMap(instance));
        }
        detail.put("recentInstances", recentInstances);
        List<Map<String, Object>> versions = new ArrayList<>();
        for (TkTaskVersion version : versionRepository.findByTaskIdAndDeletedFalseOrderByVersionNoDesc(id)) {
            versions.add(BeanMaps.toMap(version));
        }
        detail.put("versions", versions);
        detail.put("running", runningTaskRegistry.isRunning(id));
        detail.put("upstream", taskDepService.upstreamTasks(id).stream()
                .map(up -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", up.getId());
                    map.put("taskCode", up.getTaskCode());
                    map.put("taskName", up.getTaskName());
                    map.put("status", up.getStatus());
                    return map;
                }).toList());
        if (task.getSourceId() != null) {
            metadataReadService.findDataSource(task.getSourceId())
                    .ifPresent(source -> detail.put("dataSourceMeta", source));
        }
        if (task.getCatalogId() != null) {
            metadataReadService.findCatalog(task.getCatalogId())
                    .ifPresent(catalog -> detail.put("catalogMeta", catalog));
        }
        return detail;
    }

    /** 启用任务。 */
    @Transactional
    public Map<String, Object> enable(Long id) {
        return switchEnabled(id, true);
    }

    /** 停用任务。 */
    @Transactional
    public Map<String, Object> disable(Long id) {
        return switchEnabled(id, false);
    }

    /** 批量启停。 */
    @Transactional
    public Map<String, Object> batchEnable(List<Long> ids, Boolean enabled) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ids 不能为空");
        }
        int updated = 0;
        for (Long id : ids) {
            TaskRequest request = new TaskRequest();
            request.setEnabled(enabled != null && enabled);
            TkTask task = taskRepository.findByIdAndDeletedFalse(id).orElse(null);
            if (task == null) {
                continue;
            }
            task.setEnabled(request.getEnabled());
            if (Boolean.TRUE.equals(request.getEnabled()) && "DRAFT".equals(task.getStatus())) {
                task.setStatus("READY");
            }
            taskRepository.save(task);
            updated++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("enabled", enabled);
        return result;
    }

    /** 任务模板列表。 */
    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TkTemplate template : templateRepository.findByDeletedFalseOrderByIdAsc()) {
            list.add(BeanMaps.toMap(template));
        }
        if (list.isEmpty()) {
            // 模板库为空时返回内置模板定义，保证前端选择框可用
            list.addAll(builtinTemplates());
        }
        return list;
    }

    private List<Map<String, Object>> builtinTemplates() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("id", null);
        full.put("templateCode", "TPL_DB_FULL");
        full.put("templateName", "库表全量同步模板");
        full.put("category", "库表同步");
        full.put("taskType", "FULL");
        full.put("description", "H2/MySQL 库表 → 平台库表，自动建表并按批次幂等写入");
        full.put("definitionJson", "{\"sourceType\":\"H2_TABLE\",\"targetType\":\"H2_TABLE\"}");
        list.add(full);
        Map<String, Object> incremental = new LinkedHashMap<>();
        incremental.put("id", null);
        incremental.put("templateCode", "TPL_FILE_INCREMENT");
        incremental.put("templateName", "文件增量同步模板");
        incremental.put("category", "文件同步");
        incremental.put("taskType", "INCREMENT");
        incremental.put("description", "CSV 文件 → 目标表，适合前置节点文件摆渡后的入库加工");
        incremental.put("definitionJson", "{\"sourceType\":\"CSV_FILE\",\"targetType\":\"H2_TABLE\"}");
        list.add(incremental);
        return list;
    }

    /** 落任务版本快照。 */
    private void saveVersion(TkTask task, String changeType, String changeNote) {
        long count = versionRepository.countByTaskIdAndDeletedFalse(task.getId());
        TkTaskVersion version = new TkTaskVersion();
        version.setTaskId(task.getId());
        version.setTaskCode(task.getTaskCode());
        version.setVersionNo((int) count + 1);
        version.setChangeType(changeType);
        version.setChangeNote(changeNote);
        version.setDefinitionJson(JsonUtil.toJson(BeanMaps.toMap(task)));
        versionRepository.save(version);
    }

    private Map<String, Object> switchEnabled(Long id, boolean enabled) {
        TkTask task = requireTask(id);
        task.setEnabled(enabled);
        if (enabled && ("DRAFT".equals(task.getStatus()) || "FAILED".equals(task.getStatus()))) {
            task.setStatus("READY");
        }
        taskRepository.save(task);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("enabled", task.getEnabled());
        result.put("status", task.getStatus());
        return result;
    }

    private Map<String, Object> toSummary(TkTask task) {
        Map<String, Object> map = BeanMaps.toMap(task);
        Optional<TkTaskInstance> last = instanceRepository.findTop5ByTaskIdAndDeletedFalseOrderByIdDesc(task.getId())
                .stream().findFirst();
        last.ifPresent(instance -> {
            map.put("lastInstanceStatus", instance.getStatus());
            map.put("lastInstanceTime", instance.getStartTime());
            map.put("lastCostMs", instance.getCostMs());
        });
        map.put("running", runningTaskRegistry.isRunning(task.getId()));
        return map;
    }

    private void applyRequest(TkTask task, TaskRequest request) {
        if (request.getTaskCode() != null && !request.getTaskCode().isBlank()) {
            task.setTaskCode(request.getTaskCode().trim());
        }
        if (request.getTaskName() != null && !request.getTaskName().isBlank()) {
            task.setTaskName(request.getTaskName().trim());
        }
        if (request.getTaskType() != null) {
            task.setTaskType(request.getTaskType().toUpperCase());
        } else if (task.getTaskType() == null) {
            task.setTaskType("FULL");
        }
        if (request.getSourceId() != null) {
            task.setSourceId(request.getSourceId());
        }
        if (request.getSourceIncrementColumn() != null) {
            task.setSourceIncrementColumn(blankToNull(request.getSourceIncrementColumn()));
        }
        if (request.getSourceIncrementStart() != null) {
            task.setSourceIncrementStart(blankToNull(request.getSourceIncrementStart()));
        }
        if (request.getSourceName() != null) {
            task.setSourceName(request.getSourceName());
        }
        if (request.getSourceType() != null) {
            task.setSourceType(request.getSourceType().toUpperCase());
        }
        if (request.getSourceTable() != null) {
            task.setSourceTable(request.getSourceTable());
        }
        if (request.getSourceFilePath() != null) {
            task.setSourceFilePath(request.getSourceFilePath());
        }
        if (request.getTargetType() != null) {
            task.setTargetType(request.getTargetType().toUpperCase());
        }
        if (request.getTargetTable() != null) {
            task.setTargetTable(request.getTargetTable());
        }
        if (request.getTargetId() != null) {
            task.setTargetId(request.getTargetId());
        }
        if (request.getTargetFilePath() != null) {
            task.setTargetFilePath(request.getTargetFilePath());
        }
        if (request.getCatalogId() != null) {
            task.setCatalogId(request.getCatalogId());
        }
        if (request.getMapping() != null) {
            task.setMappingJson(JsonUtil.toJson(request.getMapping()));
        } else if (request.getMappingJson() != null) {
            task.setMappingJson(request.getMappingJson());
        }
        if (request.getOperators() != null) {
            task.setOperatorsJson(JsonUtil.toJson(request.getOperators()));
        } else if (request.getOperatorsJson() != null) {
            task.setOperatorsJson(request.getOperatorsJson());
        }
        if (request.getFilterCondition() != null) {
            task.setFilterCondition(request.getFilterCondition());
        }
        if (request.getScheduleCron() != null) {
            task.setScheduleCron(request.getScheduleCron().isBlank() ? null : request.getScheduleCron().trim());
        }
        if (request.getEnabled() != null) {
            task.setEnabled(request.getEnabled());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            task.setStatus(request.getStatus().toUpperCase());
        }
        if (request.getDeptId() != null) {
            task.setDeptId(request.getDeptId());
        }
        if (request.getDeptName() != null) {
            task.setDeptName(request.getDeptName());
        }
        if (request.getRemark() != null) {
            task.setRemark(request.getRemark());
        }
        // 源/目标类型未显式指定时按“表名/文件路径”推断
        if (task.getSourceType() == null || task.getSourceType().isBlank()) {
            task.setSourceType(SourceReader.resolveSourceType(task));
        }
        if (task.getTargetType() == null || task.getTargetType().isBlank()) {
            task.setTargetType(SourceReader.resolveTargetType(task));
        }
    }

    /** 在落库前校验外部 JDBC 任务是否具备必要配置。 */
    private void validateDefinition(TkTask task) {
        if ("JDBC_TABLE".equalsIgnoreCase(task.getSourceType())) {
            if (task.getSourceId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "外部 JDBC 源端必须选择数据源");
            }
            if (task.getSourceTable() == null || task.getSourceTable().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "外部 JDBC 源端必须填写源表名");
            }
        }
        if ("JDBC_TABLE".equalsIgnoreCase(task.getTargetType())) {
            if (task.getTargetId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "外部 JDBC 目标端必须选择数据源");
            }
            if (task.getTargetTable() == null || task.getTargetTable().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "外部 JDBC 目标端必须填写目标表名");
            }
        }
        if ("INCREMENT".equalsIgnoreCase(task.getTaskType())
                && !"CSV_FILE".equalsIgnoreCase(task.getSourceType())) {
            if (task.getSourceIncrementColumn() == null || task.getSourceIncrementColumn().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "增量任务必须配置 sourceIncrementColumn（时间戳或自增字段）");
            }
            SourceReader.validateIdentifier(task.getSourceIncrementColumn(), "增量字段");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TkTask requireTask(Long id) {
        return taskRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }
}
