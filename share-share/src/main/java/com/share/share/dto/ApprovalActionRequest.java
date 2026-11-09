package com.share.share.dto;

/**
 * 审批动作入参（FR-08）：approve / reject / transfer / renew 共用。
 *
 * @param comment     审批意见（reject 必填）
 * @param toUserId    转办目标用户 ID（transfer 必填）
 * @param toUserName  转办目标用户名称（可空）
 * @param newEndDate  续期后的新到期日期（renew 必填，yyyy-MM-dd）
 */
public record ApprovalActionRequest(String comment, Long toUserId, String toUserName, String newEndDate) {
}
