package com.share.task.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.task.dto.DepRequest;
import com.share.task.entity.TkTask;
import com.share.task.entity.TkTaskDep;
import com.share.task.repository.TkTaskDepRepository;
import com.share.task.repository.TkTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 任务 DAG 依赖服务（FR-04）：邻接表存储 + 保存时 DFS 环检测。
 * <p>依赖方向：taskId 为下游任务，upTaskId 为上游任务；同一任务不允许依赖自身，也不允许形成环。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDepService {

    private final TkTaskRepository taskRepository;

    private final TkTaskDepRepository depRepository;

    /** 查询任务依赖（上游 + 下游）。 */
    public Map<String, Object> dependencies(Long taskId) {
        TkTask task = requireTask(taskId);
        List<TkTaskDep> upstream = depRepository.findByTaskIdAndDeletedFalse(taskId);
        List<TkTaskDep> downstream = depRepository.findByUpTaskIdAndDeletedFalse(taskId);

        List<Map<String, Object>> upstreamList = new ArrayList<>();
        for (TkTaskDep dep : upstream) {
            Map<String, Object> item = BeanMaps.toMap(dep);
            taskRepository.findByIdAndDeletedFalse(dep.getUpTaskId()).ifPresent(up -> {
                item.put("upTaskCode", up.getTaskCode());
                item.put("upTaskName", up.getTaskName());
                item.put("upTaskStatus", up.getStatus());
                item.put("upTaskEnabled", up.getEnabled());
            });
            if (!item.containsKey("upTaskName") || item.get("upTaskName") == null) {
                item.put("upTaskName", dep.getUpTaskName());
            }
            upstreamList.add(item);
        }
        List<Map<String, Object>> downstreamList = new ArrayList<>();
        for (TkTaskDep dep : downstream) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", dep.getTaskId());
            item.put("upTaskId", dep.getUpTaskId());
            item.put("depType", dep.getDepType());
            taskRepository.findByIdAndDeletedFalse(dep.getTaskId()).ifPresent(down -> {
                item.put("taskCode", down.getTaskCode());
                item.put("taskName", down.getTaskName());
            });
            downstreamList.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("taskName", task.getTaskName());
        result.put("upstream", upstreamList);
        result.put("downstream", downstreamList);
        return result;
    }

    /** 新增依赖（支持批量），保存前做环检测。 */
    @Transactional
    public Map<String, Object> addDeps(Long taskId, DepRequest request) {
        TkTask task = requireTask(taskId);
        Set<Long> upIds = new LinkedHashSet<>();
        if (request != null) {
            if (request.getUpTaskIds() != null) {
                upIds.addAll(request.getUpTaskIds());
            }
            if (request.getUpTaskId() != null) {
                upIds.add(request.getUpTaskId());
            }
        }
        if (upIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "upTaskIds 不能为空");
        }
        String depType = request.getDepType() == null || request.getDepType().isBlank() ? "SUCCESS" : request.getDepType();
        int added = 0;
        for (Long upId : upIds) {
            if (upId == null) {
                continue;
            }
            if (upId.equals(taskId)) {
                throw new BizException(ErrorCode.TASK_DAG_CYCLE, "任务不能依赖自身");
            }
            TkTask upTask = taskRepository.findByIdAndDeletedFalse(upId)
                    .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND, "上游任务不存在：" + upId));
            if (!depRepository.findByTaskIdAndUpTaskIdAndDeletedFalse(taskId, upId).isEmpty()) {
                continue;
            }
            if (wouldCreateCycle(taskId, upId)) {
                throw new BizException(ErrorCode.TASK_DAG_CYCLE,
                        "保存依赖 [" + upTask.getTaskName() + " → " + task.getTaskName() + "] 会形成环");
            }
            TkTaskDep dep = new TkTaskDep();
            dep.setTaskId(taskId);
            dep.setUpTaskId(upId);
            dep.setUpTaskName(upTask.getTaskName());
            dep.setDepType(depType);
            depRepository.save(dep);
            added++;
        }
        Map<String, Object> result = dependencies(taskId);
        result.put("added", added);
        return result;
    }

    /** 删除依赖（逻辑删除）。 */
    @Transactional
    public Map<String, Object> removeDep(Long taskId, Long upTaskId) {
        requireTask(taskId);
        List<TkTaskDep> deps = depRepository.findByTaskIdAndUpTaskIdAndDeletedFalse(taskId, upTaskId);
        if (deps.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "依赖关系不存在");
        }
        for (TkTaskDep dep : deps) {
            dep.setDeleted(true);
            depRepository.save(dep);
        }
        Map<String, Object> result = dependencies(taskId);
        result.put("removed", deps.size());
        return result;
    }

    /** 任务被删除时清理其全部依赖边（含上下游）。 */
    @Transactional
    public void deleteAllOfTask(Long taskId) {
        List<TkTaskDep> deps = new ArrayList<>(depRepository.findByTaskIdAndDeletedFalse(taskId));
        deps.addAll(depRepository.findByUpTaskIdAndDeletedFalse(taskId));
        for (TkTaskDep dep : deps) {
            dep.setDeleted(true);
            depRepository.save(dep);
        }
    }

    /**
     * 环检测：把“新增边 taskId → upTaskId”加入图后，从 upTaskId 出发沿上游方向 DFS，
     * 若能回到 taskId 则说明形成环。
     */
    public boolean wouldCreateCycle(Long taskId, Long upTaskId) {
        Map<Long, List<Long>> graph = new HashMap<>();
        for (TkTaskDep dep : depRepository.findByDeletedFalse()) {
            graph.computeIfAbsent(dep.getTaskId(), key -> new ArrayList<>()).add(dep.getUpTaskId());
        }
        graph.computeIfAbsent(taskId, key -> new ArrayList<>()).add(upTaskId);
        Deque<Long> stack = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        stack.push(upTaskId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (current.equals(taskId)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            for (Long next : graph.getOrDefault(current, List.of())) {
                if (!visited.contains(next)) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    /** 校验任务存在。 */
    public TkTask requireTask(Long taskId) {
        return taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    }

    /** 解析上游任务（供调度按拓扑序触发时使用）。 */
    public List<TkTask> upstreamTasks(Long taskId) {
        List<TkTask> tasks = new ArrayList<>();
        for (TkTaskDep dep : depRepository.findByTaskIdAndDeletedFalse(taskId)) {
            Optional<TkTask> task = taskRepository.findByIdAndDeletedFalse(dep.getUpTaskId());
            task.ifPresent(tasks::add);
        }
        return tasks;
    }
}
