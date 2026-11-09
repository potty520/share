package com.share.share.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 提交共享申请入参（FR-08）。
 *
 * @param catalogId 申请资源目录 ID
 * @param apiId     申请共享 API ID（与 catalogId 二选一，至少一个）
 * @param purpose   申请用途
 * @param scope     使用范围
 * @param startDate 使用起始日期（yyyy-MM-dd）
 * @param endDate   使用截止日期（yyyy-MM-dd）
 * @param itemScope 数据项范围（逗号分隔）
 */
public record ApprovalSubmitRequest(
        Long catalogId,
        Long apiId,
        @NotBlank(message = "purpose 不能为空") String purpose,
        String scope,
        String startDate,
        String endDate,
        String itemScope) {
}
