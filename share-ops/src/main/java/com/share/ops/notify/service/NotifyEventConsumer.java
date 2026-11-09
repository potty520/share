package com.share.ops.notify.service;

import com.share.common.event.NotifyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 通知事件消费者（FR-14）：监听业务域发布的 {@link NotifyEvent}，落站内信并异步派发外部通道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyEventConsumer {

    private final NotifyService notifyService;

    @EventListener
    public void onNotifyEvent(NotifyEvent event) {
        if (event == null) {
            return;
        }
        try {
            notifyService.createMessage(event);
        } catch (Exception e) {
            log.warn("通知事件处理失败 title={}: {}", event.getTitle(), e.getMessage());
        }
    }
}
