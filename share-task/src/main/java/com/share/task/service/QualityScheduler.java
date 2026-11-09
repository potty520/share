package com.share.task.service;

import com.share.task.entity.TkQualityRule;
import com.share.task.repository.TkQualityRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 质量规则定时检核：扫描配置了 checkCron 的启用规则，按 cron 的下一触发时间判断是否到期。
 * <p>用 lastCheckTime 作为基准避免重复执行；同一规则串行执行，防止慢规则堆积。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "share.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class QualityScheduler {

    private final TkQualityRuleRepository ruleRepository;

    private final QualityService qualityService;

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        for (TkQualityRule rule : ruleRepository.findByDeletedFalseOrderByIdAsc()) {
            if (!Boolean.TRUE.equals(rule.getEnabled())
                    || rule.getCheckCron() == null || rule.getCheckCron().isBlank()) {
                continue;
            }
            try {
                CronExpression cron = CronExpression.parse(rule.getCheckCron().trim());
                LocalDateTime base = rule.getLastCheckTime() != null ? rule.getLastCheckTime()
                        : rule.getCreateTime() != null ? rule.getCreateTime() : now.minusMinutes(1);
                LocalDateTime next = cron.next(base);
                if (next == null || next.isAfter(now) || !running.add(rule.getId())) {
                    continue;
                }
                try {
                    qualityService.runRule(rule.getId(), "system");
                    log.info("质量规则定时检核完成 id={} code={}", rule.getId(), rule.getRuleCode());
                } finally {
                    running.remove(rule.getId());
                }
            } catch (Exception e) {
                log.warn("质量规则定时检核失败 id={} code={} msg={}", rule.getId(), rule.getRuleCode(), e.getMessage());
            }
        }
    }
}
