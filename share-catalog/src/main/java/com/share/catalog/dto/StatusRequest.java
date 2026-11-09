package com.share.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 状态变更入参（启停用）。 */
@Getter
@Setter
public class StatusRequest {

    @NotBlank(message = "状态不能为空")
    private String status;

    /** 变更原因（可选，写入变更历史） */
    private String reason;
}
