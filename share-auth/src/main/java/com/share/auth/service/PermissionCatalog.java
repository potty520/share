package com.share.auth.service;

import com.share.auth.dto.VOs;
import com.share.common.constant.PermissionConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 权限点字典（FR-13）：与 {@link PermissionConstants} 一一对应的中文名称与模块归属，
 * 用于角色权限分配界面与接口权限校验。
 */
public final class PermissionCatalog {

    /** 权限元数据：code 标识、name 中文名、module 模块编码、moduleName 模块中文名。 */
    public record Meta(String code, String name, String module, String moduleName) {
    }

    private static final List<Meta> ALL = List.of(
            new Meta(PermissionConstants.USER_READ, "查看用户与组织", "user", "用户与组织"),
            new Meta(PermissionConstants.USER_WRITE, "管理用户与组织", "user", "用户与组织"),
            new Meta(PermissionConstants.CATALOG_READ, "查看数据目录", "catalog", "数据目录"),
            new Meta(PermissionConstants.CATALOG_WRITE, "维护数据目录", "catalog", "数据目录"),
            new Meta(PermissionConstants.CATALOG_PUBLISH, "目录发布审核", "catalog", "数据目录"),
            new Meta(PermissionConstants.DATASOURCE_READ, "查看数据源", "datasource", "数据源"),
            new Meta(PermissionConstants.DATASOURCE_WRITE, "管理数据源", "datasource", "数据源"),
            new Meta(PermissionConstants.TASK_READ, "查看交换任务", "task", "交换任务"),
            new Meta(PermissionConstants.TASK_WRITE, "维护交换任务", "task", "交换任务"),
            new Meta(PermissionConstants.TASK_RUN, "执行交换任务", "task", "交换任务"),
            new Meta(PermissionConstants.QUALITY_READ, "查看质量报告", "quality", "数据质量"),
            new Meta(PermissionConstants.QUALITY_WRITE, "维护质量规则", "quality", "数据质量"),
            new Meta(PermissionConstants.SHARE_READ, "查看共享服务", "share", "共享服务"),
            new Meta(PermissionConstants.SHARE_WRITE, "维护共享服务", "share", "共享服务"),
            new Meta(PermissionConstants.APPROVAL_APPLY, "提交共享申请", "approval", "申请审批"),
            new Meta(PermissionConstants.APPROVAL_APPROVE, "审批共享申请", "approval", "申请审批"),
            new Meta(PermissionConstants.SECURITY_READ, "查看安全管控", "security", "安全管控"),
            new Meta(PermissionConstants.SECURITY_WRITE, "管理安全策略", "security", "安全管控"),
            new Meta(PermissionConstants.FILE_READ, "查看文件交换", "file", "文件交换"),
            new Meta(PermissionConstants.FILE_WRITE, "管理文件交换", "file", "文件交换"),
            new Meta(PermissionConstants.AUDIT_READ, "查看审计日志", "audit", "审计日志"),
            new Meta(PermissionConstants.MONITOR_READ, "查看监控告警", "monitor", "监控告警"),
            new Meta(PermissionConstants.MONITOR_WRITE, "维护告警规则", "monitor", "监控告警"),
            new Meta(PermissionConstants.NOTIFY_READ, "查看消息通知", "notify", "消息通知"),
            new Meta(PermissionConstants.NOTIFY_WRITE, "管理消息通道", "notify", "消息通知"),
            new Meta(PermissionConstants.AGENT_READ, "查看前置节点", "agent", "前置节点"),
            new Meta(PermissionConstants.AGENT_WRITE, "管理前置节点", "agent", "前置节点")
    );

    private static final Map<String, Meta> BY_CODE = new LinkedHashMap<>();

    static {
        ALL.forEach(meta -> BY_CODE.put(meta.code(), meta));
    }

    private PermissionCatalog() {
    }

    public static List<Meta> all() {
        return ALL;
    }

    public static Set<String> codes() {
        return BY_CODE.keySet();
    }

    public static Optional<Meta> of(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }

    public static String nameOf(String code) {
        return of(code).map(Meta::name).orElse(code);
    }

    /** 转成前端字典项。 */
    public static List<VOs.PermissionVO> toVOs() {
        return ALL.stream()
                .map(m -> new VOs.PermissionVO(m.code(), m.name(), m.module(), m.moduleName()))
                .toList();
    }
}
