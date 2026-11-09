package com.share.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 交换批次事件：任务引擎执行完成后发布，供监控告警与数据流转追溯使用（FR-11 / FR-12）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeEvent {

    private String batchId;
    private Long taskId;
    private String taskName;
    private String taskType;
    private String sourceType;
    private String targetType;
    private Long sourceId;
    private Long targetId;
    private long rowCount;
    private long byteCount;
    private boolean success;
    private long costMs;
    private String errorMsg;
    private String checksum;
    private Long operatorId;
    private Long deptId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
