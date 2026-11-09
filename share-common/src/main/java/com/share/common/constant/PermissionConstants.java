package com.share.common.constant;

/** 权限标识常量（FR-10 细粒度权限 / FR-13 RBAC）。 */
public final class PermissionConstants {

    private PermissionConstants() {
    }

    // 用户与组织
    public static final String USER_READ = "user:read";
    public static final String USER_WRITE = "user:write";

    // 数据目录（FR-03）
    public static final String CATALOG_READ = "catalog:read";
    public static final String CATALOG_WRITE = "catalog:write";
    public static final String CATALOG_PUBLISH = "catalog:publish";

    // 数据源（FR-01）
    public static final String DATASOURCE_READ = "datasource:read";
    public static final String DATASOURCE_WRITE = "datasource:write";

    // 交换任务（FR-02/04/05）
    public static final String TASK_READ = "task:read";
    public static final String TASK_WRITE = "task:write";
    public static final String TASK_RUN = "task:run";

    // 数据质量（FR-06）
    public static final String QUALITY_READ = "quality:read";
    public static final String QUALITY_WRITE = "quality:write";

    // 共享服务（FR-07）
    public static final String SHARE_READ = "share:read";
    public static final String SHARE_WRITE = "share:write";

    // 申请审批（FR-08）
    public static final String APPROVAL_APPLY = "approval:apply";
    public static final String APPROVAL_APPROVE = "approval:approve";

    // 安全（FR-10）
    public static final String SECURITY_READ = "security:read";
    public static final String SECURITY_WRITE = "security:write";

    // 文件交换（FR-09）
    public static final String FILE_READ = "file:read";
    public static final String FILE_WRITE = "file:write";

    // 审计 / 监控 / 通知（FR-11/12/14）
    public static final String AUDIT_READ = "audit:read";
    public static final String MONITOR_READ = "monitor:read";
    public static final String MONITOR_WRITE = "monitor:write";
    public static final String NOTIFY_READ = "notify:read";
    public static final String NOTIFY_WRITE = "notify:write";

    // 前置节点
    public static final String AGENT_READ = "agent:read";
    public static final String AGENT_WRITE = "agent:write";
}
