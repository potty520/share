package com.share.common.api;

import lombok.Getter;

/** 统一错误码。业务错误码按模块分段，前端可据此做差异化提示。 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),
    BAD_REQUEST(40000, "请求参数不合法"),
    UNAUTHORIZED(40100, "未认证或令牌已失效"),
    FORBIDDEN(40300, "无访问权限"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "资源状态冲突"),
    BUSINESS(42000, "业务处理失败"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁"),
    INTERNAL(50000, "系统内部错误"),

    // 认证与用户
    AUTH_BAD_CREDENTIALS(41101, "用户名或密码错误"),
    AUTH_ACCOUNT_LOCKED(41102, "账号已被锁定"),
    AUTH_ACCOUNT_DISABLED(41103, "账号已停用"),
    AUTH_MFA_REQUIRED(41104, "需要双因素认证"),
    AUTH_MFA_CODE_INVALID(41105, "动态验证码不正确"),

    // 目录
    CATALOG_NOT_FOUND(42101, "目录资源不存在"),
    CATALOG_CODE_DUPLICATED(42102, "目录资源编码已存在"),
    CATALOG_STATUS_ILLEGAL(42103, "目录当前状态不允许该操作"),
    CATALOG_NOT_PUBLISHED(42104, "目录资源尚未发布"),

    // 数据源
    DATASOURCE_NOT_FOUND(42201, "数据源不存在"),
    DATASOURCE_CONNECT_FAILED(42202, "数据源连通性测试失败"),
    DATASOURCE_IN_USE(42203, "数据源已被任务或服务引用，不能删除"),

    // 任务
    TASK_NOT_FOUND(42301, "交换任务不存在"),
    TASK_DAG_CYCLE(42302, "任务依赖存在环，无法保存"),
    TASK_STATUS_ILLEGAL(42303, "任务状态不允许该操作"),
    TASK_RUNNING(42304, "任务正在运行中"),
    TASK_ENGINE_FAILED(42305, "交换引擎执行失败"),

    // 共享服务与审批
    SHARE_API_NOT_FOUND(42401, "共享服务不存在"),
    SHARE_API_NOT_ONLINE(42402, "共享服务未上线"),
    SHARE_CREDENTIAL_INVALID(42403, "共享调用凭据无效"),
    SHARE_QPS_LIMITED(42404, "超过凭据 QPS 限额"),
    APPROVAL_NOT_FOUND(42411, "申请单不存在"),
    APPROVAL_STATUS_ILLEGAL(42412, "申请单当前状态不允许该操作"),
    APPROVAL_NO_PERMISSION(42413, "无权审批该申请"),
    GRANT_EXPIRED(42414, "授权已过期"),

    // 安全
    SEC_MASK_RULE_NOT_FOUND(42501, "脱敏规则不存在"),
    SEC_CLASSIFY_NOT_FOUND(42502, "分类分级模板不存在"),
    SEC_SENSITIVE_DENIED(42503, "涉敏数据未通过安全审核"),

    // 文件
    FILE_NOT_FOUND(42601, "文件不存在"),
    FILE_CHUNK_MISMATCH(42602, "分片校验失败"),
    FILE_CHECKSUM_MISMATCH(42603, "文件完整性校验失败"),

    // 审计 / 监控 / 通知
    AUDIT_QUERY_ILLEGAL(42701, "审计查询条件不合法"),
    ALARM_RULE_NOT_FOUND(42702, "告警规则不存在"),
    NOTIFY_NOT_FOUND(42703, "消息不存在");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
