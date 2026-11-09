package com.share.task.service;

import com.share.common.api.ErrorCode;
import com.share.common.event.ExchangeEvent;
import com.share.common.exception.BizException;
import com.share.task.dto.RunTicket;
import com.share.task.dto.ScheduleRequest;
import com.share.task.engine.RunningTaskRegistry;
import com.share.task.entity.TkTask;
import com.share.task.entity.TkTaskDep;
import com.share.task.entity.TkTaskInstance;
import com.share.task.repository.TkTaskDepRepository;
import com.share.task.repository.TkTaskInstanceRepository;
import com.share.task.repository.TkTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务调度服务（FR-04）：ThreadPoolTaskScheduler 每 60 秒扫描一次启用任务，
 * 用 CronExpression 解析 cron 并触发实例；同时消费 ExchangeEvent 按 DAG 依赖触发下游任务。
 */
@Slf4j
@Service
public class ScheduleService {

    private final TkTaskRepository taskRepository;

    private final TkTaskDepRepository depRepository;

    private final TkTaskInstanceRepository instanceRepository;

    private final InstanceService instanceService;

    private final RunningTaskRegistry runningTaskRegistry;

    private final ThreadPoolTaskScheduler scheduler;

    /** 上次扫描时间：只触发 (lastScan, now] 区间内的 cron 时点，避免重复触发。 */
    private volatile LocalDateTime lastScan = LocalDateTime.now();

    public ScheduleService(TkTaskRepository taskRepository,
                           TkTaskDepRepository depRepository,
                           TkTaskInstanceRepository instanceRepository,
                           InstanceService instanceService,
                           RunningTaskRegistry runningTaskRegistry,
                           @Qualifier("shareTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.taskRepository = taskRepository;
        this.depRepository = depRepository;
        this.instanceRepository = instanceRepository;
        this.instanceService = instanceService;
        this.runningTaskRegistry = runningTaskRegistry;
        this.scheduler = scheduler;
    }

    /** 启动 cron 扫描线程。 */
    @PostConstruct
    public void startScanner() {
        scheduler.scheduleAtFixedRate(this::scanSafely, Duration.ofSeconds(60));
        log.info("交换任务调度扫描器已启动（60 秒周期，CronExpression 解析）");
    }

    /** 设置任务调度计划。 */
    public Map<String, Object> setSchedule(Long taskId, ScheduleRequest request) {
        TkTask task = taskRepository.findByIdAndDeletedFalse(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
        String cron = request == null ? null : request.getCron();
        boolean enabled = request == null || request.getEnabled() == null || request.getEnabled();
        if (cron != null && !cron.isBlank()) {
            try {
                CronExpression.parse(cron.trim());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "cron 表达式不合法：" + cron);
            }
            task.setScheduleCron(cron.trim());
            task.setEnabled(enabled);
            if (enabled && "DRAFT".equals(task.getStatus())) {
                task.setStatus("READY");
            }
        } else {
            task.setScheduleCron(null);
            if (!enabled) {
                task.setEnabled(false);
            }
        }
        taskRepository.save(task);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("taskName", task.getTaskName());
        result.put("scheduleCron", task.getScheduleCron());
        result.put("enabled", task.getEnabled());
        result.put("status", task.getStatus());
        result.put("nextFireTime", nextFireTime(task.getScheduleCron()));
        return result;
    }

    /** 调度计划列表（含下次触发时间）。 */
    public List<Map<String, Object>> schedules() {
        List<Map<String, Object>> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (TkTask task : taskRepository.findByDeletedFalseOrderByIdDesc()) {
            if (task.getScheduleCron() == null || task.getScheduleCron().isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getId());
            item.put("taskCode", task.getTaskCode());
            item.put("taskName", task.getTaskName());
            item.put("scheduleCron", task.getScheduleCron());
            item.put("enabled", task.getEnabled());
            item.put("status", task.getStatus());
            item.put("deptName", task.getDeptName());
            item.put("lastRunTime", task.getLastRunTime());
            item.put("lastBatchId", task.getLastBatchId());
            item.put("running", runningTaskRegistry.isRunning(task.getId()));
            item.put("nextFireTime", nextFireTime(task.getScheduleCron(), now));
            list.add(item);
        }
        return list;
    }

    /** 每分钟扫描一次，命中 cron 时点则触发。 */
    private void scanSafely() {
        try {
            scan();
        } catch (Exception e) {
            log.warn("调度扫描异常：{}", e.getMessage());
        }
    }

    private void scan() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime base = lastScan;
        int triggered = 0;
        for (TkTask task : taskRepository.findByDeletedFalseAndEnabledTrueOrderByIdAsc()) {
            String cron = task.getScheduleCron();
            if (cron == null || cron.isBlank()) {
                continue;
            }
            CronExpression expression;
            try {
                expression = CronExpression.parse(cron.trim());
            } catch (IllegalArgumentException e) {
                log.warn("任务 {} cron 表达式非法：{}", task.getId(), cron);
                continue;
            }
            LocalDateTime next = expression.next(base);
            int guard = 0;
            while (next != null && !next.isAfter(now) && guard < 10) {
                guard++;
                trigger(task);
                triggered++;
                next = expression.next(next);
            }
        }
        lastScan = now;
        if (triggered > 0) {
            log.info("调度扫描完成，本轮触发 {} 个任务实例", triggered);
        }
    }

    private void trigger(TkTask task) {
        if (runningTaskRegistry.isRunning(task.getId())) {
            log.info("任务 {} 上一实例仍在运行，本轮调度跳过", task.getId());
            return;
        }
        try {
            RunTicket ticket = instanceService.triggerByCron(task);
            log.info("cron 触发任务 {}（{}），批次 {} 实例 {}",
                    task.getTaskCode(), task.getScheduleCron(), ticket.getBatchId(), ticket.getInstanceId());
        } catch (Exception e) {
            log.warn("cron 触发任务 {} 失败：{}", task.getTaskCode(), e.getMessage());
        }
    }

    /**
     * 依赖编排：上游任务执行成功后，若下游任务启用且未配置独立 cron，
     * 且其全部上游最近一次实例均成功，则自动触发下游（按 DAG 顺序执行）。
     */
    @EventListener
    public void onExchangeEvent(ExchangeEvent event) {
        if (!event.isSuccess() || event.getTaskId() == null) {
            return;
        }
        List<TkTaskDep> downstream = depRepository.findByUpTaskIdAndDeletedFalse(event.getTaskId());
        for (TkTaskDep dep : downstream) {
            TkTask task = taskRepository.findByIdAndDeletedFalse(dep.getTaskId()).orElse(null);
            if (task == null || !Boolean.TRUE.equals(task.getEnabled())) {
                continue;
            }
            if (task.getScheduleCron() != null && !task.getScheduleCron().isBlank()) {
                // 有独立 cron 计划的任务按自己的节奏运行，避免依赖触发与 cron 触发重复
                continue;
            }
            if (runningTaskRegistry.isRunning(task.getId())) {
                continue;
            }
            if (!allUpstreamSuccess(task.getId())) {
                log.info("下游任务 {} 尚有上游未成功，暂不触发", task.getTaskCode());
                continue;
            }
            try {
                RunTicket ticket = instanceService.triggerByDependency(task);
                log.info("依赖触发下游任务 {}，批次 {}，实例 {}",
                        task.getTaskCode(), ticket.getBatchId(), ticket.getInstanceId());
            } catch (Exception e) {
                log.warn("依赖触发下游任务 {} 失败：{}", task.getTaskCode(), e.getMessage());
            }
        }
    }

    /** 校验下游任务的全部上游最近实例是否成功。 */
    private boolean allUpstreamSuccess(Long taskId) {
        for (TkTaskDep dep : depRepository.findByTaskIdAndDeletedFalse(taskId)) {
            List<TkTaskInstance> instances = instanceRepository.findByTaskIdAndDeletedFalseOrderByIdDesc(dep.getUpTaskId());
            if (instances.isEmpty() || !"SUCCESS".equals(instances.get(0).getStatus())) {
                return false;
            }
        }
        return true;
    }

    private LocalDateTime nextFireTime(String cron) {
        return nextFireTime(cron, LocalDateTime.now());
    }

    private LocalDateTime nextFireTime(String cron, LocalDateTime base) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        try {
            return CronExpression.parse(cron.trim()).next(base);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
