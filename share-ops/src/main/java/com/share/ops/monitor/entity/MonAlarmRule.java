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
 * 告警规则（FR-12）：阈值 + 持续时间 + 级别（P0~P3）+ 通知渠道。
 */
@Getter
@Setter
@Entity
@Table(name = "mon_alarm_rule", indexes = {
        @Index(name = "idx_mon_rule_type", columnList = "metric_type")
})
public class MonAlarmRule extends BaseEntity {

    @Column(name = "rule_name", nullable = false, length = 200)
    private String ruleName;

    /** 指标类型：TASK_FAIL_RATE（任务失败率）/ NODE_OFFLINE（节点离线）/ API_ERROR_RATE（API 错误率） */
    @Column(name = "metric_type", length = 32)
    private String metricType;

    /** 比较符：GT / GE / LT / LE / EQ / NE */
    @Column(name = "operator", length = 8)
    private String operator;

    /** 阈值 */
    @Column(name = "threshold")
    private Double threshold;

    /** 持续时间（秒）：指标持续满足阈值多久才触发告警 */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** 告警级别：P0 / P1 / P2 / P3 */
    @Column(name = "alarm_level", length = 8)
    private String alarmLevel;

    /** 通知渠道（逗号分隔）：INNER,EMAIL,SMS,WEBHOOK */
    @Column(name = "channels", length = 200)
    private String channels;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 监控对象过滤：任务 ID / 节点编码，为空表示全部 */
    @Column(name = "target_code", length = 64)
    private String targetCode;

    @Column(name = "description", length = 500)
    private String description;

    /** 最近一次评估时间 */
    @Column(name = "last_eval_time")
    private LocalDateTime lastEvalTime;
}
