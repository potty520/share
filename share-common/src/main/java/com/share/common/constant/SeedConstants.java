package com.share.common.constant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置角色、演示账号与角色权限矩阵（各域种子数据共用，保证演示环境一致）。
 */
public final class SeedConstants {

    private SeedConstants() {
    }

    // ===== 角色 =====
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_PROVIDER = "PROVIDER";
    public static final String ROLE_CONSUMER = "CONSUMER";
    public static final String ROLE_SECURITY = "SECURITY";
    public static final String ROLE_OPS = "OPS";
    public static final String ROLE_BIZ_ADMIN = "BIZ_ADMIN";

    // ===== 演示用户 =====
    public static final long ADMIN_ID = 1L;
    public static final long PROVIDER_ID = 2L;
    public static final long CONSUMER_ID = 3L;
    public static final long SECURITY_ID = 4L;
    public static final long OPS_ID = 5L;
    public static final long BIZ_ADMIN_ID = 6L;

    public static final String ADMIN_USERNAME = "admin";
    public static final String PROVIDER_USERNAME = "provider";
    public static final String CONSUMER_USERNAME = "consumer";
    public static final String SECURITY_USERNAME = "security";
    public static final String OPS_USERNAME = "ops";
    public static final String BIZ_ADMIN_USERNAME = "bizadmin";

    /** 演示环境统一初始口令（首次登录后应强制修改）。 */
    public static final String DEMO_PASSWORD = "123456";

    // ===== 组织 =====
    public static final long DEPT_DATA_BUREAU = 1L;
    public static final long DEPT_TAX = 2L;
    public static final long DEPT_MARKET = 3L;
    public static final long DEPT_HRSS = 4L;
    public static final long DEPT_PLATFORM = 9L;

    /** 角色 → 权限矩阵。ADMIN 在鉴权时整体放行，同时入库便于展示。 */
    public static final Map<String, String[]> ROLE_PERMISSIONS = Map.of(
            ROLE_ADMIN, new String[]{
                    PermissionConstants.USER_READ, PermissionConstants.USER_WRITE,
                    PermissionConstants.CATALOG_READ, PermissionConstants.CATALOG_WRITE, PermissionConstants.CATALOG_PUBLISH,
                    PermissionConstants.DATASOURCE_READ, PermissionConstants.DATASOURCE_WRITE,
                    PermissionConstants.TASK_READ, PermissionConstants.TASK_WRITE, PermissionConstants.TASK_RUN,
                    PermissionConstants.QUALITY_READ, PermissionConstants.QUALITY_WRITE,
                    PermissionConstants.SHARE_READ, PermissionConstants.SHARE_WRITE,
                    PermissionConstants.APPROVAL_APPLY, PermissionConstants.APPROVAL_APPROVE,
                    PermissionConstants.SECURITY_READ, PermissionConstants.SECURITY_WRITE,
                    PermissionConstants.FILE_READ, PermissionConstants.FILE_WRITE,
                    PermissionConstants.AUDIT_READ, PermissionConstants.MONITOR_READ, PermissionConstants.MONITOR_WRITE,
                    PermissionConstants.NOTIFY_READ, PermissionConstants.NOTIFY_WRITE,
                    PermissionConstants.AGENT_READ, PermissionConstants.AGENT_WRITE
            },
            ROLE_PROVIDER, new String[]{
                    PermissionConstants.CATALOG_READ, PermissionConstants.CATALOG_WRITE, PermissionConstants.CATALOG_PUBLISH,
                    PermissionConstants.DATASOURCE_READ, PermissionConstants.DATASOURCE_WRITE,
                    PermissionConstants.TASK_READ, PermissionConstants.TASK_RUN,
                    PermissionConstants.QUALITY_READ, PermissionConstants.QUALITY_WRITE,
                    PermissionConstants.SHARE_READ, PermissionConstants.APPROVAL_APPROVE,
                    PermissionConstants.FILE_READ, PermissionConstants.FILE_WRITE, PermissionConstants.NOTIFY_READ
            },
            ROLE_CONSUMER, new String[]{
                    PermissionConstants.CATALOG_READ, PermissionConstants.SHARE_READ,
                    PermissionConstants.APPROVAL_APPLY, PermissionConstants.FILE_READ,
                    PermissionConstants.TASK_READ, PermissionConstants.NOTIFY_READ
            },
            ROLE_SECURITY, new String[]{
                    PermissionConstants.SECURITY_READ, PermissionConstants.SECURITY_WRITE,
                    PermissionConstants.CATALOG_READ, PermissionConstants.AUDIT_READ,
                    PermissionConstants.APPROVAL_APPROVE, PermissionConstants.MONITOR_READ,
                    PermissionConstants.NOTIFY_READ
            },
            ROLE_OPS, new String[]{
                    PermissionConstants.TASK_READ, PermissionConstants.TASK_WRITE, PermissionConstants.TASK_RUN,
                    PermissionConstants.MONITOR_READ, PermissionConstants.MONITOR_WRITE,
                    PermissionConstants.AUDIT_READ, PermissionConstants.NOTIFY_READ,
                    PermissionConstants.FILE_READ, PermissionConstants.FILE_WRITE,
                    PermissionConstants.AGENT_READ, PermissionConstants.AGENT_WRITE
            },
            ROLE_BIZ_ADMIN, new String[]{
                    PermissionConstants.CATALOG_READ, PermissionConstants.CATALOG_PUBLISH,
                    PermissionConstants.APPROVAL_APPROVE, PermissionConstants.SHARE_READ,
                    PermissionConstants.QUALITY_READ, PermissionConstants.NOTIFY_READ
            }
    );

    /** 演示数据源 / 目录 / 任务等种子数据归属部门。 */
    public static List<Long> demoDepts() {
        return List.of(DEPT_DATA_BUREAU, DEPT_TAX, DEPT_MARKET, DEPT_HRSS);
    }

    public static Set<String> allPermissions() {
        return Set.of(
                PermissionConstants.USER_READ, PermissionConstants.USER_WRITE,
                PermissionConstants.CATALOG_READ, PermissionConstants.CATALOG_WRITE, PermissionConstants.CATALOG_PUBLISH,
                PermissionConstants.DATASOURCE_READ, PermissionConstants.DATASOURCE_WRITE,
                PermissionConstants.TASK_READ, PermissionConstants.TASK_WRITE, PermissionConstants.TASK_RUN,
                PermissionConstants.QUALITY_READ, PermissionConstants.QUALITY_WRITE,
                PermissionConstants.SHARE_READ, PermissionConstants.SHARE_WRITE,
                PermissionConstants.APPROVAL_APPLY, PermissionConstants.APPROVAL_APPROVE,
                PermissionConstants.SECURITY_READ, PermissionConstants.SECURITY_WRITE,
                PermissionConstants.FILE_READ, PermissionConstants.FILE_WRITE,
                PermissionConstants.AUDIT_READ, PermissionConstants.MONITOR_READ, PermissionConstants.MONITOR_WRITE,
                PermissionConstants.NOTIFY_READ, PermissionConstants.NOTIFY_WRITE,
                PermissionConstants.AGENT_READ, PermissionConstants.AGENT_WRITE
        );
    }
}
