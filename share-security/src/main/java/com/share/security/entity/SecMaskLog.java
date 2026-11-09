package com.share.security.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 脱敏执行日志（FR-10）：只记录脱敏前后的摘要与结果样本，不落敏感原文。
 */
@Getter
@Setter
@Entity
@Table(name = "sec_mask_log", indexes = {
        @Index(name = "idx_masklog_time", columnList = "mask_time")
})
public class SecMaskLog extends BaseEntity {

    @Column(name = "rule_code", length = 64)
    private String ruleCode;

    @Column(name = "mask_type", length = 16)
    private String maskType;

    @Column(name = "target_field", length = 100)
    private String targetField;

    /** 脱敏后的值（样本，截断 500 字符） */
    @Column(name = "masked_value", length = 500)
    private String maskedValue;

    /** 原始值 SHA-256 摘要（前 32 位），用于追溯但不泄露原文 */
    @Column(name = "value_digest", length = 64)
    private String valueDigest;

    /** 来源：TEST 试算 / MASK_ENGINE 引擎 / API 动态脱敏 */
    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "mask_time")
    private LocalDateTime maskTime;

    @Column(name = "cost_ms")
    private Long costMs;

    @Column(name = "remark", length = 500)
    private String remark;
}
