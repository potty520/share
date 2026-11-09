package com.share.catalog.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 目录审核入参。 */
@Getter
@Setter
public class AuditRequest {

    /** true=通过并发布；false=驳回 */
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    /** 审核意见 */
    private String comment;
}
