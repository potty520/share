package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 数据流转日志（FR-02 / FR-11）：按阶段记录一次交换中“读源 → 加工 → 脱敏 → 写目标 → 核对”
 * 的关键动作与行数，可按 batchId 还原数据流转链路。
 */
@Getter
@Setter
@Entity
@Table(name = "sw_transfer_log")
public class SwTransferLog extends BaseEntity {

    /** 批次号 */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    /** 任务 ID */
    @Column(name = "task_id")
    private Long taskId;

    /** 实例 ID */
    @Column(name = "instance_id")
    private Long instanceId;

    /** 阶段：READ / TRANSFORM / OPERATOR / MASK / WRITE / VERIFY / FINISH */
    @Column(name = "stage", length = 20)
    private String stage;

    /** 日志级别：INFO / WARN / ERROR */
    @Column(name = "level", length = 10)
    private String level = "INFO";

    /** 日志内容 */
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /** 该阶段处理行数 */
    @Column(name = "rows_count")
    private Long rowsCount;

    /** 该阶段耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 日志时间 */
    @Column(name = "log_time")
    private LocalDateTime logTime;

    /** 明细 JSON（如映射后的列清单、脱敏字段清单） */
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;
}
