package com.share.share.constant;

import com.share.common.constant.SeedConstants;

/**
 * 共享服务与审批域的状态/类型常量（FR-07 / FR-08）。
 * <p>状态机：
 * <ul>
 *   <li>共享 API：DRAFT（草稿）→ ONLINE（上线）→ OFFLINE（下线）</li>
 *   <li>申请单：PENDING（审批中）→ APPROVED / REJECTED / WITHDRAWN</li>
 *   <li>审批节点：PENDING → APPROVED / REJECTED / SKIPPED</li>
 *   <li>授权：ACTIVE → EXPIRED（到期） / REVOKED（回收）</li>
 *   <li>凭据：ACTIVE → REVOKED / EXPIRED</li>
 * </ul>
 * </p>
 */
public final class ShareConstants {

    private ShareConstants() {
    }

    // ===== 共享 API 状态 =====
    public static final String API_DRAFT = "DRAFT";
    public static final String API_ONLINE = "ONLINE";
    public static final String API_OFFLINE = "OFFLINE";

    // ===== 凭据状态 =====
    public static final String CRED_ACTIVE = "ACTIVE";
    public static final String CRED_REVOKED = "REVOKED";
    public static final String CRED_EXPIRED = "EXPIRED";

    // ===== 申请单状态 =====
    public static final String APPLY_PENDING = "PENDING";
    public static final String APPLY_APPROVED = "APPROVED";
    public static final String APPLY_REJECTED = "REJECTED";
    public static final String APPLY_WITHDRAWN = "WITHDRAWN";

    // ===== 审批节点状态 =====
    public static final String NODE_PENDING = "PENDING";
    public static final String NODE_APPROVED = "APPROVED";
    public static final String NODE_REJECTED = "REJECTED";
    public static final String NODE_SKIPPED = "SKIPPED";

    // ===== 审批节点类型 =====
    public static final String NODE_TYPE_BIZ_ADMIN = "BIZ_ADMIN";
    public static final String NODE_TYPE_PROVIDER = "PROVIDER";
    public static final String NODE_TYPE_SECURITY = "SECURITY";
    public static final String NODE_TYPE_RENEW = "RENEW";

    // ===== 授权状态 =====
    public static final String GRANT_ACTIVE = "ACTIVE";
    public static final String GRANT_EXPIRED = "EXPIRED";
    public static final String GRANT_REVOKED = "REVOKED";

    // ===== 调用统计 =====
    /** 开放网关调用 */
    public static final String CHANNEL_OPEN = "OPEN";
    /** 管理端在线调试 */
    public static final String CHANNEL_DEBUG = "DEBUG";
    public static final String STAT_SUCCESS = "SUCCESS";
    public static final String STAT_FAIL = "FAIL";

    // ===== 审批角色（与内置角色一致） =====
    public static final String ROLE_BIZ_ADMIN = SeedConstants.ROLE_BIZ_ADMIN;
    public static final String ROLE_PROVIDER = SeedConstants.ROLE_PROVIDER;
    public static final String ROLE_SECURITY = SeedConstants.ROLE_SECURITY;
    public static final String ROLE_ADMIN = SeedConstants.ROLE_ADMIN;

    /** 角色 → 演示账号用户 ID（审批待办通知用；真实环境应由用户域按角色查询）。 */
    public static Long demoUserIdOfRole(String roleCode) {
        if (roleCode == null) {
            return null;
        }
        return switch (roleCode) {
            case SeedConstants.ROLE_ADMIN -> SeedConstants.ADMIN_ID;
            case SeedConstants.ROLE_PROVIDER -> SeedConstants.PROVIDER_ID;
            case SeedConstants.ROLE_CONSUMER -> SeedConstants.CONSUMER_ID;
            case SeedConstants.ROLE_SECURITY -> SeedConstants.SECURITY_ID;
            case SeedConstants.ROLE_OPS -> SeedConstants.OPS_ID;
            case SeedConstants.ROLE_BIZ_ADMIN -> SeedConstants.BIZ_ADMIN_ID;
            default -> null;
        };
    }

    /** 角色 → 演示账号用户名（用于通知展示）。 */
    public static String demoUsernameOfRole(String roleCode) {
        if (roleCode == null) {
            return null;
        }
        return switch (roleCode) {
            case SeedConstants.ROLE_ADMIN -> SeedConstants.ADMIN_USERNAME;
            case SeedConstants.ROLE_PROVIDER -> SeedConstants.PROVIDER_USERNAME;
            case SeedConstants.ROLE_CONSUMER -> SeedConstants.CONSUMER_USERNAME;
            case SeedConstants.ROLE_SECURITY -> SeedConstants.SECURITY_USERNAME;
            case SeedConstants.ROLE_OPS -> SeedConstants.OPS_USERNAME;
            case SeedConstants.ROLE_BIZ_ADMIN -> SeedConstants.BIZ_ADMIN_USERNAME;
            default -> roleCode;
        };
    }
}
