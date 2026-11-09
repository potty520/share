package com.share.share.dto;

/**
 * 授权回收入参（FR-08）。
 *
 * @param reason 回收原因
 */
public record GrantRevokeRequest(String reason) {
}
