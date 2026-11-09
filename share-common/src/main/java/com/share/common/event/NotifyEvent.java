package com.share.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 通知事件（站内信 / 邮件 / 短信 / Webhook），由业务域发布，share-ops 消费。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyEvent {

    /** 接收人用户 ID；为空表示广播。 */
    private Long receiverId;
    /** 消息标题 */
    private String title;
    /** 消息内容 */
    private String content;
    /** 业务类型：APPROVAL / ALARM / EXPIRY / ANNOUNCEMENT / FEEDBACK / TASK */
    private String bizType;
    private String bizId;
    /** 渠道：INNER / EMAIL / SMS / WEBHOOK，默认 INNER */
    private String channel;
    /** 消息级别：INFO / WARN / ERROR */
    private String level;
    /** 跳转链接 */
    private String link;
}
