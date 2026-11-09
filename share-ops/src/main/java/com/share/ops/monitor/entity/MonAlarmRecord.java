package com.share.ops.monitor.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 告警记录（FR-12）：规则评估命中后生成，支持确认（ack）闭环。
 */
@Getter
@Setter
@Entity
@Table(name = "mon_alarm_record", indexes = {
        @Index(name = "idx_mon_alarm_rule", columnList = "rule_id"),
        @Index(name = "idx_mon_alarm_status", columnList = "status"),
        @Index(name = "idx_mon_alarm_first_time", columnList = "first_time")
})
public class MonAlarmRecord extends BaseEntity {

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_name", length = 200)
    private String ruleName;

    /** 指标类型，同规则 */
    @Column(name = "metric_type", length = 32)
    private String metricType;

    /** 告警级别：P0 / P1 / P2 / P3 */
    @Column(name = "alarm_level", length = 8)
    private String alarmLevel;

    /** 触发时的指标实测值 */
    @Column(name = "metric_value")
    private Double metricValue;

    /** 规则阈值快照 */
    @Column(name = "threshold_value")
    private Double thresholdValue;

    /** 告警内容（长文本，含对象与建议处置） */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** OPEN（未确认）/ ACKED（已确认）/ CLOSED（已恢复） */
    @Column(name = "status", length = 16)
    private String status;

    /** 监控对象类型：TASK / NODE / API / PLATFORM */
    @Column(name = "object_type", length = 16)
    private String objectType;

    /** 监控对象标识：任务 ID、节点编码、API ID 等 */
    @Column(name = "object_code", length = 64)
    private String objectCode;

    @Column(name = "first_time")
    private LocalDateTime firstTime;

    @Column(name = "last_time")
    private LocalDateTime lastTime;

    /** 触发次数（持续未恢复时累加） */
    @Column(name = "trigger_count")
    private Integer triggerCount;

    /** 通知发送状态：SENT / FAILED / SKIPPED */
    @Column(name = "notify_status", length = 16)
    private String notifyStatus;

    @Column(name = "ack_by", length = 64)
    private String ackBy;

    @Column(name = "ack_time")
    private LocalDateTime ackTime;

    @Column(name = "ack_remark", length = 500)
    private String ackRemark;
}
