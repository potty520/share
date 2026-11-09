package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 交换断点（FR-02 断点续传）：按分片记录已完成的读取偏移，失败重试/续跑时可跳过已处理分片。
 */
@Getter
@Setter
@Entity
@Table(name = "sw_checkpoint")
public class SwCheckpoint extends BaseEntity {

    /** 任务 ID */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 批次号 */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    /** 分片序号（从 1 开始） */
    @Column(name = "chunk_no")
    private Integer chunkNo;

    /** 已处理行数（偏移） */
    @Column(name = "offset_row")
    private Long offsetRow;

    /** 当前批次总行数 */
    @Column(name = "total_rows")
    private Long totalRows;

    /** 状态：RUNNING / DONE */
    @Column(name = "state", length = 20)
    private String state = "RUNNING";

    /** 断点时间 */
    @Column(name = "checkpoint_time")
    private LocalDateTime checkpointTime;

    /** 备注（如重试次数） */
    @Column(name = "remark", length = 500)
    private String remark;
}
