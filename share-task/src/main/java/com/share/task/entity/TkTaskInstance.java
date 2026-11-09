package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务运行实例（FR-04）：记录每次执行的进度、行数、耗时、校验值与核对结果。
 */
@Getter
@Setter
@Entity
@Table(name = "tk_task_instance")
public class TkTaskInstance extends BaseEntity {

    /** 所属任务 ID */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 任务编码 */
    @Column(name = "task_code", length = 64)
    private String taskCode;

    /** 任务名称（冗余） */
    @Column(name = "task_name", length = 200)
    private String taskName;

    /** 交换批次号 */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    /** 触发方式：MANUAL / CRON / DEPENDENCY / RERUN */
    @Column(name = "trigger_type", length = 20)
    private String triggerType;

    /** 实例状态：RUNNING / SUCCESS / FAILED / STOPPED */
    @Column(name = "status", length = 20)
    private String status;

    /** 执行进度（0~100） */
    @Column(name = "progress")
    private Integer progress = 0;

    /** 源端读取行数 */
    @Column(name = "read_rows")
    private Long readRows = 0L;

    /** 过滤后待写入行数 */
    @Column(name = "total_rows")
    private Long totalRows = 0L;

    /** 成功写入行数 */
    @Column(name = "write_rows")
    private Long writeRows = 0L;

    /** 失败行数 */
    @Column(name = "failed_rows")
    private Long failedRows = 0L;

    /** 数据校验值（MD5） */
    @Column(name = "checksum", length = 64)
    private String checksum;

    /** 一致性核对状态：PASS / FAIL / NOT_VERIFIED */
    @Column(name = "verify_status", length = 20)
    private String verifyStatus = "NOT_VERIFIED";

    /** 核对结果明细 */
    @Column(name = "verify_result", columnDefinition = "TEXT")
    private String verifyResult;

    /** 错误信息 */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    /** 开始时间 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 结束时间 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 触发人用户 ID */
    @Column(name = "operator_id")
    private Long operatorId;

    /** 触发人用户名 */
    @Column(name = "operator_name", length = 64)
    private String operatorName;

    /** 归属部门 ID */
    @Column(name = "dept_id")
    private Long deptId;
}
