package com.share.security.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 脱敏规则（FR-10）：规则 = 算法类型 + 参数 + 生效字段 + 匹配正则 + 最低密级。
 * <p>交换任务静态脱敏与共享 API 动态脱敏统一按规则执行。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "sec_mask_rule")
public class SecMaskRule extends BaseEntity {

    @Column(name = "rule_code", nullable = false, length = 64)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    /** 算法：MASK/REPLACE/HASH/GENERALIZE/PERTURB/ENCRYPT */
    @Column(name = "mask_type", nullable = false, length = 16)
    private String maskType;

    /** 算法参数：MASK 的 "3,4"、REPLACE 的 "***"、GENERALIZE 的 "NUM:100/DATE:MONTH/ADDR" */
    @Column(name = "param", length = 200)
    private String param;

    /** 生效字段名（可为空表示按匹配规则识别） */
    @Column(name = "target_field", length = 100)
    private String targetField;

    /** 字段值匹配正则（可选） */
    @Column(name = "match_pattern", length = 300)
    private String matchPattern;

    /** 触发脱敏的最低密级 */
    @Column(name = "level_min")
    private Integer levelMin = 1;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
