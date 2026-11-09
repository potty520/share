package com.share.share.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 授权到期扫描定时任务（FR-08）。
 * <p>每日 02:00 扫描授权：到期前 7 天发到期提醒通知，已到期自动置 EXPIRED 并吊销调用凭据。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "share.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ApprovalExpiryScheduler {

    private final GrantService grantService;

    /** 每日 02:00 执行授权到期扫描（T-7 提醒 + 到期回收）。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scanGrants() {
        try {
            log.info("开始执行授权到期扫描任务");
            grantService.scanExpiringGrants();
        } catch (Exception e) {
            log.error("授权到期扫描任务执行失败", e);
        }
    }
}
