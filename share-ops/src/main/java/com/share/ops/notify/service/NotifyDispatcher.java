package com.share.ops.notify.service;

import com.share.ops.notify.entity.NotifyChannel;
import com.share.ops.notify.entity.NotifyMessage;
import com.share.ops.notify.repository.NotifyChannelRepository;
import com.share.ops.notify.repository.NotifyMessageRepository;
import com.share.ops.support.OpsConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部通知通道异步派发器（FR-14）。
 * <p>站内信落库后由本组件异步投递 EMAIL/SMS/WEBHOOK，超时/失败只回写发送状态，不阻塞发布方业务线程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDispatcher {

    private final NotifyMessageRepository messageRepository;
    private final NotifyChannelRepository channelRepository;
    private final ChannelSender channelSender;

    @Async
    public void dispatch(Long messageId, List<String> channelTypes) {
        NotifyMessage message = messageRepository.findById(messageId).orElse(null);
        if (message == null || channelTypes == null || channelTypes.isEmpty()) {
            return;
        }
        boolean anySent = false;
        boolean anyFail = false;
        List<String> errors = new ArrayList<>();
        for (String type : channelTypes) {
            List<NotifyChannel> channels = channelRepository
                    .findByChannelTypeAndEnabledTrueAndDeletedFalse(type);
            if (channels.isEmpty()) {
                errors.add(type + "：无可用通道");
                continue;
            }
            for (NotifyChannel channel : channels) {
                ChannelSender.SendResult result = channelSender.send(channel,
                        message.getTitle(), message.getContent(), receiverOf(message));
                channel.setLastSendTime(LocalDateTime.now());
                channel.setLastSendStatus(result.status());
                channelRepository.save(channel);
                if (result.success()) {
                    anySent = true;
                } else if (OpsConstants.SEND_FAILED.equals(result.status())) {
                    anyFail = true;
                    errors.add(channel.getChannelCode() + "：" + result.error());
                } else {
                    errors.add(channel.getChannelCode() + "：" + result.error());
                }
            }
        }
        String status = anyFail ? OpsConstants.SEND_FAILED
                : (anySent ? OpsConstants.SEND_SENT : OpsConstants.SEND_SKIPPED);
        message.setSendStatus(status);
        message.setSendError(truncate(String.join("; ", errors), 480));
        messageRepository.save(message);
        log.info("通知消息 {} 外部通道派发完成，状态={}", messageId, status);
    }

    /** 接收人标识：优先消息绑定 ID，其次接收人名称 */
    private String receiverOf(NotifyMessage message) {
        if (message.getReceiverId() != null) {
            return String.valueOf(message.getReceiverId());
        }
        return message.getReceiverName();
    }

    private String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
