package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 质量问题工单（FR-06）：检核发现问题后自动建单，支持整改闭环跟踪。
 */
@Getter
@Setter
@Entity
@Table(name = "tk_quality_issue")
public class TkQualityIssue extends BaseEntity {

    /** 关联报告 ID */
    @Column(name = "report_id")
    private Long reportId;

    /** 规则 ID */
    @Column(name = "rule_id")
    private Long ruleId;

    /** 规则名称（冗余） */
    @Column(name = "rule_name", length = 200)
    private String ruleName;

    /** 规则类型（冗余） */
    @Column(name = "rule_type", length = 20)
    private String ruleType;

    /** 目标表 */
    @Column(name = "target_table", length = 128)
    private String targetTable;

    /** 目标数据源 ID（为空表示平台内置库表） */
    @Column(name = "target_id")
    private Long targetId;

    /** 目标字段 */
    @Column(name = "target_field", length = 128)
    private String targetField;

    /** 问题级别：HIGH / MEDIUM / LOW */
    @Column(name = "issue_level", length = 10)
    private String issueLevel;

    /** 问题描述 */
    @Column(name = "issue_desc", length = 500)
    private String issueDesc;

    /** 问题样例数据 */
    @Column(name = "sample_data", columnDefinition = "TEXT")
    private String sampleData;

    /** 工单状态：OPEN / CLOSED */
    @Column(name = "status", length = 20)
    private String status = "OPEN";

    /** 发现时间 */
    @Column(name = "found_time")
    private LocalDateTime foundTime;

    /** 关闭时间 */
    @Column(name = "close_time")
    private LocalDateTime closeTime;

    /** 关闭人 */
    @Column(name = "close_by", length = 64)
    private String closeBy;

    /** 整改说明 */
    @Column(name = "close_remark", length = 500)
    private String closeRemark;
}
