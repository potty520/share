package com.share.share.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 凭据签发入参（FR-07）。
 *
 * @param apiId        共享 API ID
 * @param consumerId   使用方用户 ID
 * @param consumerName 使用方名称
 * @param expireDays   有效天数，默认 90
 * @param grantLevel   授权密级（为空时取目录密级）
 * @param remark       备注
 */
public record CredentialCreateRequest(
        @NotNull(message = "apiId 不能为空") Long apiId,
        Long consumerId,
        String consumerName,
        Integer expireDays,
        Integer grantLevel,
        String remark) {
}
