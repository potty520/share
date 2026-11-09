package com.share.ops.monitor.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 指标点（FR-12）：按小时聚合的时序指标，EXCHANGE / API / NODE 三类。
 * <p>唯一键 = 指标类型 + 指标名 + 维度类型 + 维度键 + 小时，保证重复事件做累加而不是重复行；
 * 维度类型必须参与唯一键，否则 TASK 维度 key=2 与 DEPT 维度 key=2 会互相串数据。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "mon_metric_point",
        uniqueConstraints = @UniqueConstraint(name = "uk_mon_point",
                columnNames = {"metric_type", "metric_name", "dimension_type", "dimension_key", "stat_time"}),
        indexes = {
                @Index(name = "idx_mon_point_type_time", columnList = "metric_type,stat_time"),
                @Index(name = "idx_mon_point_dim", columnList = "dimension_key")
        })
public class MonMetricPoint extends BaseEntity {

    /** EXCHANGE（交换）/ API（服务调用）/ NODE（节点） */
    @Column(name = "metric_type", nullable = false, length = 32)
    private String metricType;

    /** 指标名称：EXCHANGE_BATCH / API_CALL / NODE_ONLINE / NODE_CPU */
    @Column(name = "metric_name", nullable = false, length = 64)
    private String metricName;

    /** 维度类型：TASK / DEPT / API / NODE / ALL */
    @Column(name = "dimension_type", length = 16)
    private String dimensionType;

    /** 维度键：任务 ID、部门 ID、API ID、节点编码，ALL 表示全平台 */
    @Column(name = "dimension_key", nullable = false, length = 64)
    private String dimensionKey;

    /** 小时起点（如 2026-11-09 14:00:00） */
    @Column(name = "stat_time", nullable = false)
    private LocalDateTime statTime;

    /** 指标值（百分比/均值等） */
    @Column(name = "metric_value")
    private Double metricValue;

    /** 采样次数（批次/调用数/心跳数） */
    @Column(name = "sample_count")
    private Long sampleCount;

    @Column(name = "success_count")
    private Long successCount;

    @Column(name = "fail_count")
    private Long failCount;

    /** 累计行数（交换量） */
    @Column(name = "total_row_count")
    private Long totalRowCount;

    /** 累计耗时（毫秒，用于均值） */
    @Column(name = "total_cost_ms")
    private Long totalCostMs;
}
