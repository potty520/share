package com.share.ops.audit.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作审计日志（FR-11）：由 {@code AuditEvent} 事件驱动落库，覆盖登录、配置变更、任务操作、审批、导出等行为。
 */
@Getter
@Setter
@Entity
@Table(name = "aud_operation_log", indexes = {
        @Index(name = "idx_aud_operator", columnList = "operator_name"),
        @Index(name = "idx_aud_action", columnList = "action"),
        @Index(name = "idx_aud_module", columnList = "module"),
        @Index(name = "idx_aud_operate_time", columnList = "operate_time"),
        @Index(name = "idx_aud_result", columnList = "result")
})
public class AudOperationLog extends BaseEntity {

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 64)
    private String operatorName;

    @Column(name = "role_code", length = 32)
    private String roleCode;

    /** 业务模块：catalog / task / approval / file / security / monitor 等 */
    @Column(name = "module", length = 64)
    private String module;

    /** 操作动作：CATALOG_PUBLISH、TASK_TRIGGER 等 */
    @Column(name = "action", length = 64)
    private String action;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    /** 请求参数/操作详情（长文本，可能超过 4000 字符） */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** SUCCESS / FAIL */
    @Column(name = "result", length = 16)
    private String result;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "request_method", length = 16)
    private String requestMethod;

    @Column(name = "uri", length = 300)
    private String uri;

    /** 耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 操作发生时间（事件携带，缺失时取入库时间） */
    @Column(name = "operate_time")
    private LocalDateTime operateTime;
}
