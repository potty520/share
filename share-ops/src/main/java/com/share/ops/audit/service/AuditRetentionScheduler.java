package com.share.ops.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 审计日志保留期清理任务：默认每日 03:40 执行，保留天数由 share.monitor.audit-retention-days 控制。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "share.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuditRetentionScheduler {

    private final AuditService auditService;

    @Value("${share.monitor.audit-cleanup-mode:LOGICAL}")
    private String cleanupMode = "LOGICAL";

    @Scheduled(cron = "${share.monitor.audit-cleanup-cron:0 40 3 * * ?}")
    public void cleanup() {
        try {
            auditService.cleanupExpired(null, cleanupMode);
        } catch (Exception e) {
            log.warn("审计日志保留期清理失败：{}", e.getMessage());
        }
    }
}
