package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 数据质量规则（FR-06）：完整性/唯一性/规范性/一致性/及时性五类内置规则。
 */
@Getter
@Setter
@Entity
@Table(name = "tk_quality_rule")
public class TkQualityRule extends BaseEntity {

    /** 规则编码 */
    @Column(name = "rule_code", length = 64, nullable = false)
    private String ruleCode;

    /** 规则名称 */
    @Column(name = "rule_name", length = 200, nullable = false)
    private String ruleName;

    /** 规则类型：COMPLETENESS / UNIQUENESS / FORMAT / CONSISTENCY / TIMELINESS */
    @Column(name = "rule_type", length = 20)
    private String ruleType;

    /** 关联任务 ID（为空表示直接检核目标表） */
    @Column(name = "task_id")
    private Long taskId;

    /** 检核目标表 */
    @Column(name = "target_table", length = 128)
    private String targetTable;

    /** 检核字段 */
    @Column(name = "target_field", length = 128)
    private String targetField;

    /** 表达式：FORMAT 为正则、CONSISTENCY 为布尔 SQL 表达式、TIMELINESS 为时间字段 */
    @Column(name = "expression", length = 500)
    private String expression;

    /** 阈值：完整性/唯一性为允许问题占比，及时性为允许延迟小时数 */
    @Column(name = "threshold")
    private Double threshold;

    /** 是否启用 */
    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    /** 定时检核 cron（为空则仅手工执行） */
    @Column(name = "check_cron", length = 64)
    private String checkCron;

    /** 规则说明 */
    @Column(name = "description", length = 500)
    private String description;

    /** 最近检核时间 */
    @Column(name = "last_check_time")
    private LocalDateTime lastCheckTime;

    /** 最近检核得分 */
    @Column(name = "last_score")
    private Double lastScore;
}
