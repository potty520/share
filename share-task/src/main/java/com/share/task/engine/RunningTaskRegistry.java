package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行实例注册表：维护“任务 → 当前实例”的占用关系与实例取消标记，
 * 支撑任务运行互斥（同一任务不允许并发实例）与手工终止。
 */
@Component
public class RunningTaskRegistry {

    /** taskId → 当前实例 ID */
    private final Map<Long, Long> taskInstances = new ConcurrentHashMap<>();

    /** instanceId → 取消标记 */
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 登记实例开始运行；同一任务重复运行直接拒绝。 */
    public void register(Long taskId, Long instanceId) {
        Long existing = taskInstances.putIfAbsent(taskId, instanceId);
        if (existing != null) {
            throw new BizException(ErrorCode.TASK_RUNNING, "任务已有运行中的实例（实例 ID " + existing + "）");
        }
        cancelFlags.put(instanceId, new AtomicBoolean(false));
    }

    /** 实例结束时清理。 */
    public void unregister(Long taskId, Long instanceId) {
        taskInstances.remove(taskId, instanceId);
        cancelFlags.remove(instanceId);
    }

    /** 任务是否正在运行。 */
    public boolean isRunning(Long taskId) {
        return taskInstances.containsKey(taskId);
    }

    /** 任务当前运行实例 ID。 */
    public Long runningInstanceId(Long taskId) {
        return taskInstances.get(taskId);
    }

    /** 请求终止实例；返回是否成功（未登记则说明已结束）。 */
    public boolean cancel(Long instanceId) {
        AtomicBoolean flag = cancelFlags.get(instanceId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    /** 引擎在各阶段检查取消标记，命中则抛出终止异常。 */
    public void checkCancelled(Long instanceId) {
        AtomicBoolean flag = cancelFlags.get(instanceId);
        if (flag != null && flag.get()) {
            throw new StopRequestedException("任务被手工终止");
        }
    }

    /** 当前运行中的实例 ID 集合。 */
    public Set<Long> runningInstanceIds() {
        return new java.util.HashSet<>(cancelFlags.keySet());
    }

    /** 查询任务正在运行的实例 ID 列表（供监控/接口展示）。 */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<>();
        taskInstances.forEach((taskId, instanceId) ->
                list.add(Map.of("taskId", taskId, "instanceId", instanceId, "status", "RUNNING")));
        return list;
    }
}
