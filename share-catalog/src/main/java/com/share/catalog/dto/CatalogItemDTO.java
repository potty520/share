package com.share.catalog.dto;

import lombok.Getter;
import lombok.Setter;

/** 目录数据项入参。 */
@Getter
@Setter
public class CatalogItemDTO {

    /** 字段名，建议与挂接表列名一致 */
    private String fieldName;

    /** 字段类型 */
    private String fieldType;

    /** 字段说明 */
    private String fieldComment;

    /** 样例值 */
    private String sampleValue;

    /** 是否敏感 */
    private Boolean sensitive;

    /** 脱敏规则编码 */
    private String maskRuleCode;

    /** 排序号 */
    private Integer sortNo;
}
