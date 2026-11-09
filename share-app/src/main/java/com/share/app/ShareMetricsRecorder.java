package com.share.app;

import com.share.common.audit.AuditEvent;
import com.share.common.event.ExchangeEvent;
import com.share.common.event.NotifyEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 业务指标：把交换、审计、通知事件转成 Prometheus 计数器。 */
@Component
@RequiredArgsConstructor
public class ShareMetricsRecorder {

    private final MeterRegistry meterRegistry;

    @EventListener
    public void onExchange(ExchangeEvent event) {
        if (event == null) {
            return;
        }
        meterRegistry.counter("share.exchange.batches",
                "success", String.valueOf(event.isSuccess())).increment();
        meterRegistry.counter("share.exchange.rows").increment(event.getRowCount());
        meterRegistry.counter("share.exchange.bytes").increment(event.getByteCount());
        if (!event.isSuccess()) {
            meterRegistry.counter("share.exchange.failures").increment();
        }
    }

    @EventListener
    public void onAudit(AuditEvent event) {
        if (event == null) {
            return;
        }
        meterRegistry.counter("share.audit.events",
                "module", safe(event.getModule()),
                "result", safe(event.getResult())).increment();
    }

    @EventListener
    public void onNotify(NotifyEvent event) {
        if (event == null) {
            return;
        }
        meterRegistry.counter("share.notify.events",
                "bizType", safe(event.getBizType()),
                "level", safe(event.getLevel())).increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
