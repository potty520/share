package com.share.ops.monitor.service;

import com.share.common.event.ExchangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 交换事件消费者（FR-12）：监听任务引擎发布的 {@link ExchangeEvent}，按小时聚合到指标点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "share.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class MonitorEventConsumer {

    private final MetricService metricService;

    @EventListener
    public void onExchangeEvent(ExchangeEvent event) {
        if (event == null) {
            return;
        }
        try {
            metricService.recordExchange(event);
        } catch (Exception e) {
            log.warn("交换事件指标聚合失败 batch={}: {}", event.getBatchId(), e.getMessage());
        }
    }
}
