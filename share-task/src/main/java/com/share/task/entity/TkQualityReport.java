package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 质量检核报告（FR-06）：每次执行规则产出得分、问题行数与问题明细。
 */
@Getter
@Setter
@Entity
@Table(name = "tk_quality_report")
public class TkQualityReport extends BaseEntity {

    /** 规则 ID */
    @Column(name = "rule_id")
    private Long ruleId;

    /** 规则名称（冗余） */
    @Column(name = "rule_name", length = 200)
    private String ruleName;

    /** 规则类型（冗余） */
    @Column(name = "rule_type", length = 20)
    private String ruleType;

    /** 关联任务 ID */
    @Column(name = "task_id")
    private Long taskId;

    /** 检核目标表 */
    @Column(name = "target_table", length = 128)
    private String targetTable;

    /** 检核字段 */
    @Column(name = "target_field", length = 128)
    private String targetField;

    /** 检核时间 */
    @Column(name = "check_time")
    private LocalDateTime checkTime;

    /** 检核总行数 */
    @Column(name = "total_rows")
    private Long totalRows = 0L;

    /** 问题行数 */
    @Column(name = "problem_rows")
    private Long problemRows = 0L;

    /** 质量得分（0~100） */
    @Column(name = "score")
    private Double score;

    /** 是否达标 */
    @Column(name = "pass_flag")
    private Boolean passFlag;

    /** 问题明细 JSON（TopN 异常值及出现次数） */
    @Column(name = "issue_detail_json", columnDefinition = "TEXT")
    private String issueDetailJson;

    /** 检核耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 检核人 */
    @Column(name = "checker", length = 64)
    private String checker;
}
