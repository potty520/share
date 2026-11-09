package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 字段映射规则（FR-04 字段级映射）：
 * transform 支持直接映射（空）、UPPER、LOWER、CONST:xx、TO_DATE、MASK_PHONE、MASK_IDCARD 等；
 * maskType/maskParam 表示映射后调用 MaskService 做静态脱敏（MASK/REPLACE/HASH/GENERALIZE/PERTURB/ENCRYPT）。
 */
@Getter
@Setter
public class MappingRule {

    /** 源字段名 */
    private String sourceField;

    /** 目标字段名 */
    private String targetField;

    /** 转换规则 */
    private String transform;

    /** 脱敏算法类型（为空表示不脱敏） */
    private String maskType;

    /** 脱敏算法参数，如 MASK 的 "3,4" */
    private String maskParam;

    public MappingRule() {
    }

    public MappingRule(String sourceField, String targetField, String transform) {
        this.sourceField = sourceField;
        this.targetField = targetField;
        this.transform = transform;
    }
}
