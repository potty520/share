package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 质量规则新增/修改请求（FR-06）。
 * <p>targetTable/targetField 必填；FORMAT 用 expression 存正则，CONSISTENCY 用 expression 存布尔表达式，
 * TIMELINESS 用 targetField 存时间字段、threshold 存允许延迟小时数。</p>
 */
@Getter
@Setter
public class QualityRuleRequest {

    private String ruleCode;
    private String ruleName;
    private String ruleType;
    private Long taskId;
    private Long targetId;
    private String targetTable;
    private String targetField;
    private String expression;
    private Double threshold;
    private Boolean enabled;
    private String checkCron;
    private String description;
}
