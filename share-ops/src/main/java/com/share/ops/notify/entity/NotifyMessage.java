package com.share.ops.notify.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内消息（FR-14）：由 {@code NotifyEvent} 事件落库，receiverId 为空表示广播消息。
 */
@Getter
@Setter
@Entity
@Table(name = "notify_message", indexes = {
        @Index(name = "idx_notify_msg_receiver", columnList = "receiver_id"),
        @Index(name = "idx_notify_msg_read", columnList = "read_flag"),
        @Index(name = "idx_notify_msg_create_time", columnList = "create_time")
})
public class NotifyMessage extends BaseEntity {

    /** 接收人用户 ID；为空 = 广播 */
    @Column(name = "receiver_id")
    private Long receiverId;

    @Column(name = "receiver_name", length = 64)
    private String receiverName;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    /** 消息正文（长文本） */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 业务类型：APPROVAL / ALARM / EXPIRY / ANNOUNCEMENT / FEEDBACK / TASK / FILE */
    @Column(name = "biz_type", length = 32)
    private String bizType;

    @Column(name = "biz_id", length = 128)
    private String bizId;

    /** 通道：INNER / EMAIL / SMS / WEBHOOK */
    @Column(name = "channel", length = 16)
    private String channel;

    /** 级别：INFO / WARN / ERROR */
    @Column(name = "msg_level", length = 16)
    private String msgLevel;

    /** 前端跳转链接 */
    @Column(name = "link", length = 300)
    private String link;

    @Column(name = "read_flag", nullable = false)
    private Boolean readFlag = false;

    @Column(name = "read_time")
    private LocalDateTime readTime;

    /** 外部通道发送状态：SENT / FAILED / SKIPPED */
    @Column(name = "send_status", length = 16)
    private String sendStatus;

    @Column(name = "send_error", length = 500)
    private String sendError;

    /** 来源：EVENT（事件驱动）/ SEED（种子）/ SYSTEM（系统推送） */
    @Column(name = "source", length = 16)
    private String source;
}
