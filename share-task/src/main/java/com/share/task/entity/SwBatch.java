package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 交换批次（FR-02 / FR-04）：一次数据搬运的权威记录，是幂等判重、一致性核对、
 * 数据流转追溯（share-ops 审计 / 监控）的核心依据。
 */
@Getter
@Setter
@Entity
@Table(name = "sw_batch")
public class SwBatch extends BaseEntity {

    /** 全局唯一批次号（IdGen.batchId()） */
    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    /** 任务 ID */
    @Column(name = "task_id")
    private Long taskId;

    /** 任务名称 */
    @Column(name = "task_name", length = 200)
    private String taskName;

    /** 任务类型：FULL / INCREMENT */
    @Column(name = "task_type", length = 20)
    private String taskType;

    /** 源类型：H2_TABLE / CSV_FILE */
    @Column(name = "source_type", length = 20)
    private String sourceType;

    /** 目标类型：H2_TABLE / CSV_FILE */
    @Column(name = "target_type", length = 20)
    private String targetType;

    /** 源数据源 ID */
    @Column(name = "source_id")
    private Long sourceId;

    /** 目标表名 / 文件路径 */
    @Column(name = "target_table", length = 500)
    private String targetTable;

    /** 写入的数据列（逗号分隔，用于一致性核对复算） */
    @Column(name = "columns_json", columnDefinition = "TEXT")
    private String columnsJson;

    /** 源端读取行数 */
    @Column(name = "read_rows")
    private Long readRows = 0L;

    /** 过滤后待写入行数 */
    @Column(name = "total_rows")
    private Long totalRows = 0L;

    /** 成功写入行数 */
    @Column(name = "success_rows")
    private Long successRows = 0L;

    /** 失败行数 */
    @Column(name = "failed_rows")
    private Long failedRows = 0L;

    /** 数据字节数（估算） */
    @Column(name = "byte_count")
    private Long byteCount = 0L;

    /** 批次耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 校验值（数据行 MD5） */
    @Column(name = "checksum", length = 64)
    private String checksum;

    /** 批次状态：RUNNING / SUCCESS / FAILED / STOPPED */
    @Column(name = "status", length = 20)
    private String status;

    /** 错误信息 */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    /** 开始时间 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 结束时间 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 归属部门 ID */
    @Column(name = "dept_id")
    private Long deptId;

    /** 触发人用户 ID */
    @Column(name = "operator_id")
    private Long operatorId;
}
