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
 * 外部通知通道配置（FR-14）：EMAIL / SMS / WEBHOOK，配置以 JSON 存储。
 */
@Getter
@Setter
@Entity
@Table(name = "notify_channel", indexes = {
        @Index(name = "idx_notify_channel_type", columnList = "channel_type")
})
public class NotifyChannel extends BaseEntity {

    @Column(name = "channel_code", nullable = false, length = 64)
    private String channelCode;

    @Column(name = "channel_name", length = 200)
    private String channelName;

    /** EMAIL / SMS / WEBHOOK */
    @Column(name = "channel_type", length = 16)
    private String channelType;

    /**
     * 配置 JSON（长文本），按类型不同：
     * <ul>
     *   <li>EMAIL：{"host":"smtp.xx","port":465,"username":"","password":"","from":"","ssl":true}</li>
     *   <li>SMS：{"endpoint":"","appKey":"","appSecret":"","sign":""}</li>
     *   <li>WEBHOOK：{"url":"https://...","method":"POST","token":""}</li>
     * </ul>
     */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "last_send_time")
    private LocalDateTime lastSendTime;

    @Column(name = "last_send_status", length = 16)
    private String lastSendStatus;
}
