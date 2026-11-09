package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 交换任务定义（FR-04）：源配置 + 字段映射 + 算子链 + 目标配置 + 调度策略。
 * <p>映射规则与算子链以 JSON 字符串保存（mapping_json / operators_json），便于画布与 API 双入口。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "tk_task")
public class TkTask extends BaseEntity {

    /** 任务编码（业务唯一） */
    @Column(name = "task_code", length = 64, nullable = false)
    private String taskCode;

    /** 任务名称 */
    @Column(name = "task_name", length = 200, nullable = false)
    private String taskName;

    /** 任务类型：FULL 全量 / INCREMENT 增量 */
    @Column(name = "task_type", length = 20)
    private String taskType;

    /** 源数据源 ID（跨域只读 ds_source 时使用） */
    @Column(name = "source_id")
    private Long sourceId;

    /** 源端增量字段（时间戳/自增 ID），INCREMENT 任务必填 */
    @Column(name = "source_increment_column", length = 128)
    private String sourceIncrementColumn;

    /** 首次增量起点，为空表示从源端全量数据开始 */
    @Column(name = "source_increment_start", length = 128)
    private String sourceIncrementStart;

    /** 最近一次成功增量水位（仅成功后更新） */
    @Column(name = "last_watermark", length = 128)
    private String lastWatermark;

    /** 源数据源名称（冗余展示） */
    @Column(name = "source_name", length = 200)
    private String sourceName;

    /** 源类型：H2_TABLE / CSV_FILE */
    @Column(name = "source_type", length = 20)
    private String sourceType;

    /** 源表名（源为库表时使用，内置样例表 tk_demo_*） */
    @Column(name = "source_table", length = 128)
    private String sourceTable;

    /** 源文件路径（源为 CSV 时使用） */
    @Column(name = "source_file_path", length = 500)
    private String sourceFilePath;

    /** 目标类型：H2_TABLE / CSV_FILE */
    @Column(name = "target_type", length = 20)
    private String targetType;

    /** 目标表名（目标为库表时使用） */
    @Column(name = "target_table", length = 128)
    private String targetTable;

    /** 目标数据源 ID；为空时写入平台库 */
    @Column(name = "target_id")
    private Long targetId;

    /** 目标文件路径（目标为 CSV 时使用） */
    @Column(name = "target_file_path", length = 500)
    private String targetFilePath;

    /** 关联目录资源 ID（跨域只读 cat_catalog 取挂接信息时使用） */
    @Column(name = "catalog_id")
    private Long catalogId;

    /** 字段映射规则 JSON：[{sourceField,targetField,transform,maskType,maskParam}] */
    @Column(name = "mapping_json", columnDefinition = "TEXT")
    private String mappingJson;

    /** 算子链 JSON：[{type,params:{...}}] */
    @Column(name = "operators_json", columnDefinition = "TEXT")
    private String operatorsJson;

    /** 源端过滤条件，如 status = 'NORMAL' */
    @Column(name = "filter_condition", length = 500)
    private String filterCondition;

    /** 调度 cron（Spring 6 段表达式），为空表示不定时调度 */
    @Column(name = "schedule_cron", length = 64)
    private String scheduleCron;

    /** 是否启用 */
    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    /** 任务状态：DRAFT / READY / RUNNING / FAILED */
    @Column(name = "status", length = 20)
    private String status = "DRAFT";

    /** 归属部门 ID */
    @Column(name = "dept_id")
    private Long deptId;

    /** 归属部门名称 */
    @Column(name = "dept_name", length = 100)
    private String deptName;

    /** 最近一次运行时间 */
    @Column(name = "last_run_time")
    private LocalDateTime lastRunTime;

    /** 最近一次批次号 */
    @Column(name = "last_batch_id", length = 64)
    private String lastBatchId;

    /** 备注 */
    @Column(name = "remark", length = 500)
    private String remark;
}
