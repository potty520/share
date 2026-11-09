package com.share.app;

import com.share.common.audit.AuditEvent;
import com.share.common.event.ExchangeEvent;
import com.share.common.event.NotifyEvent;
import com.share.common.util.JsonUtil;
import com.share.ops.audit.service.AuditService;
import com.share.ops.monitor.service.MetricService;
import com.share.ops.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 事件总线桥接：业务域仍发布 Spring 事件，开启 Kafka 后统一投递到 topic；
 * 消费端调用原审计/指标/通知服务，保证事件语义和本地模式一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "share.kafka.enabled", havingValue = "true")
public class KafkaEventBridge {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AuditService auditService;
    private final MetricService metricService;
    private final NotifyService notifyService;

    @Value("${share.kafka.topics.audit:share.audit}")
    private String auditTopic;

    @Value("${share.kafka.topics.exchange:share.exchange}")
    private String exchangeTopic;

    @Value("${share.kafka.topics.notify:share.notify}")
    private String notifyTopic;

    @EventListener
    public void onAudit(AuditEvent event) {
        send(auditTopic, event == null ? null : event.getAction(), event);
    }

    @EventListener
    public void onExchange(ExchangeEvent event) {
        send(exchangeTopic, event == null ? null : event.getBatchId(), event);
    }

    @EventListener
    public void onNotify(NotifyEvent event) {
        send(notifyTopic, event == null ? null : event.getBizId(), event);
    }

    @KafkaListener(topics = "${share.kafka.topics.audit:share.audit}",
            groupId = "${spring.kafka.consumer.group-id:share-exchange}")
    public void consumeAudit(ConsumerRecord<String, String> record) {
        try {
            AuditEvent event = JsonUtil.fromJson(record.value(), AuditEvent.class);
            if (event != null) {
                auditService.record(event);
            }
        } catch (Exception e) {
            log.error("Kafka 审计事件消费失败 key={}: {}", record.key(), e.getMessage());
        }
    }

    @KafkaListener(topics = "${share.kafka.topics.exchange:share.exchange}",
            groupId = "${spring.kafka.consumer.group-id:share-exchange}")
    public void consumeExchange(ConsumerRecord<String, String> record) {
        try {
            ExchangeEvent event = JsonUtil.fromJson(record.value(), ExchangeEvent.class);
            if (event != null) {
                metricService.recordExchange(event);
            }
        } catch (Exception e) {
            log.error("Kafka 交换事件消费失败 key={}: {}", record.key(), e.getMessage());
        }
    }

    @KafkaListener(topics = "${share.kafka.topics.notify:share.notify}",
            groupId = "${spring.kafka.consumer.group-id:share-exchange}")
    public void consumeNotify(ConsumerRecord<String, String> record) {
        try {
            NotifyEvent event = JsonUtil.fromJson(record.value(), NotifyEvent.class);
            if (event != null) {
                notifyService.createMessage(event);
            }
        } catch (Exception e) {
            log.error("Kafka 通知事件消费失败 key={}: {}", record.key(), e.getMessage());
        }
    }

    private void send(String topic, String key, Object event) {
        if (event == null) {
            return;
        }
        try {
            kafkaTemplate.send(topic, key == null ? "event" : key, JsonUtil.toJson(event))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Kafka 事件投递失败 topic={} key={}: {}", topic, key, error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Kafka 事件投递异常 topic={}: {}", topic, e.getMessage());
        }
    }
}
