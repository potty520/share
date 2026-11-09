package com.share.ops.file.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件传输日志（FR-09）：每次真实复制/收发的字节数、耗时、校验值。
 */
@Getter
@Setter
@Entity
@Table(name = "fl_transfer_log", indexes = {
        @Index(name = "idx_fl_log_task", columnList = "task_id"),
        @Index(name = "idx_fl_log_create_time", columnList = "create_time")
})
public class FlTransferLog extends BaseEntity {

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_name", length = 200)
    private String taskName;

    /** 传输批次号（沿用 IdGen.batchId 规则，便于与交换任务关联） */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "source_file", length = 600)
    private String sourceFile;

    @Column(name = "target_file", length = 600)
    private String targetFile;

    @Column(name = "file_size")
    private Long fileSize;

    /** 传输后目标文件 MD5（与源端比对，保证不丢不错） */
    @Column(name = "md5", length = 64)
    private String md5;

    /** SUCCESS / FAIL */
    @Column(name = "status", length = 32)
    private String status;

    /** 耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 触发方式：MANUAL / CRON / WATCH */
    @Column(name = "trigger_type", length = 16)
    private String triggerType;

    @Column(name = "retry_count")
    private Integer retryCount;

    /** 失败原因/详情（长文本） */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "start_time")
    private java.time.LocalDateTime startTime;

    @Column(name = "end_time")
    private java.time.LocalDateTime endTime;
}
