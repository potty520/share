package com.share.common.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 操作审计事件（由各业务域发布，share-ops 消费入库）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String result;
    private String errorMsg;
    private Long operatorId;
    private String operatorName;
    private String roleCode;
    private String ip;
    private String userAgent;
    private String module;
    private String method;
    private String uri;
    private long costMs;
    private String traceId;
    private LocalDateTime operateTime;
}
