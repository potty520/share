package com.share.ops.file.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件收发调度器（FR-09）：每分钟扫描一次，处理 cron 到点任务与目录监听任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferScheduler {

    private final TransferService transferService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        try {
            List<Long> triggered = transferService.tickScheduled();
            if (!triggered.isEmpty()) {
                log.info("cron 触发文件收发任务: {}", triggered);
            }
        } catch (Exception e) {
            log.warn("文件收发 cron 调度异常: {}", e.getMessage());
        }
        try {
            List<Long> triggered = transferService.tickWatch();
            if (!triggered.isEmpty()) {
                log.info("目录监听触发文件收发任务: {}", triggered);
            }
        } catch (Exception e) {
            log.warn("目录监听调度异常: {}", e.getMessage());
        }
    }
}
