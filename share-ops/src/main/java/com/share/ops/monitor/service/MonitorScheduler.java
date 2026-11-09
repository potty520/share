package com.share.ops.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 监控采集与告警评估调度器（FR-12）：每分钟刷新节点状态、聚合节点/API 指标并评估告警规则。
 * <p>每一步都独立 try/catch，单步异常不影响后续步骤与主业务线程。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "share.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MonitorScheduler {

    private final MonitorService monitorService;
    private final MetricService metricService;
    private final AlarmService alarmService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void tick() {
        try {
            monitorService.refreshNodeStatus();
        } catch (Exception e) {
            log.warn("节点状态刷新失败: {}", e.getMessage());
        }
        try {
            metricService.collectNodeMetrics();
        } catch (Exception e) {
            log.warn("节点指标采集失败: {}", e.getMessage());
        }
        try {
            metricService.collectApiMetrics();
        } catch (Exception e) {
            log.warn("API 指标采集失败: {}", e.getMessage());
        }
        try {
            monitorService.warmupHourlyPoints();
        } catch (Exception e) {
            log.debug("交换指标补点失败: {}", e.getMessage());
        }
        try {
            alarmService.evaluate();
        } catch (Exception e) {
            log.warn("告警规则评估失败: {}", e.getMessage());
        }
    }
}
