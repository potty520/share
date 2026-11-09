package com.share.ops.monitor.repository;

import com.share.ops.monitor.entity.MonMetricPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 指标点仓储（按小时聚合的时序数据）。
 * <p>注意：同一事件会同时写入 TASK、DEPT 等多个维度，因此所有汇总查询都必须固定 dimensionType，
 * 否则跨维度求和会重复计量。</p>
 */
public interface MonMetricPointRepository extends JpaRepository<MonMetricPoint, Long> {

    /** 唯一键定位（用于 upsert 累加，维度类型必须参与定位，避免跨维度串数据） */
    Optional<MonMetricPoint> findByMetricTypeAndMetricNameAndDimensionTypeAndDimensionKeyAndStatTime(
            String metricType, String metricName, String dimensionType, String dimensionKey,
            LocalDateTime statTime);

    List<MonMetricPoint> findByMetricTypeAndStatTimeBetweenOrderByStatTimeAsc(
            String metricType, LocalDateTime from, LocalDateTime to);

    List<MonMetricPoint> findByMetricTypeAndDimensionTypeAndStatTimeBetweenOrderByStatTimeAsc(
            String metricType, String dimensionType, LocalDateTime from, LocalDateTime to);

    long countByDeletedFalse();

    /** 区间内交换行数合计（任务维度） */
    @Query("select coalesce(sum(p.totalRowCount), 0) from MonMetricPoint p where p.deleted = false "
            + "and p.metricType = 'EXCHANGE' and p.metricName = 'EXCHANGE_BATCH' and p.dimensionType = 'TASK' "
            + "and p.statTime >= :from and p.statTime < :to")
    Long sumExchangeRows(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内按维度键汇总（部门/任务排行），维度类型由调用方指定 */
    @Query("select p.dimensionKey, coalesce(sum(p.totalRowCount), 0), coalesce(sum(p.sampleCount), 0), "
            + "coalesce(sum(p.successCount), 0), coalesce(sum(p.failCount), 0) "
            + "from MonMetricPoint p where p.deleted = false and p.metricType = 'EXCHANGE' "
            + "and p.metricName = 'EXCHANGE_BATCH' and p.dimensionType = :dimensionType "
            + "and p.statTime >= :from and p.statTime < :to group by p.dimensionKey "
            + "order by coalesce(sum(p.totalRowCount), 0) desc")
    List<Object[]> groupExchangeByDimension(@Param("dimensionType") String dimensionType,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    /** 区间内交换批次成功/失败汇总（任务维度，不重复） */
    @Query("select coalesce(sum(p.sampleCount), 0), coalesce(sum(p.successCount), 0), "
            + "coalesce(sum(p.failCount), 0), coalesce(sum(p.totalCostMs), 0) "
            + "from MonMetricPoint p where p.deleted = false and p.metricType = 'EXCHANGE' "
            + "and p.metricName = 'EXCHANGE_BATCH' and p.dimensionType = 'TASK' "
            + "and p.statTime >= :from and p.statTime < :to")
    List<Object[]> sumExchangeStat(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内 API 调用汇总 */
    @Query("select coalesce(sum(p.sampleCount), 0), coalesce(sum(p.successCount), 0), "
            + "coalesce(sum(p.failCount), 0), coalesce(sum(p.totalCostMs), 0) "
            + "from MonMetricPoint p where p.deleted = false and p.metricType = 'API' "
            + "and p.dimensionType = 'ALL' and p.statTime >= :from and p.statTime < :to")
    List<Object[]> sumApiStat(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按小时汇总某类型指标（固定维度类型，供趋势图） */
    @Query("select p.statTime, coalesce(sum(p.metricValue), 0), coalesce(sum(p.sampleCount), 0), "
            + "coalesce(sum(p.successCount), 0), coalesce(sum(p.failCount), 0), "
            + "coalesce(sum(p.totalRowCount), 0) from MonMetricPoint p where p.deleted = false "
            + "and p.metricType = :metricType and p.dimensionType = :dimensionType "
            + "and p.statTime >= :from and p.statTime < :to "
            + "group by p.statTime order by p.statTime")
    List<Object[]> sumByHour(@Param("metricType") String metricType,
                             @Param("dimensionType") String dimensionType,
                             @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 最近一个指标点（告警评估/总览兜底） */
    Optional<MonMetricPoint> findTopByMetricTypeAndMetricNameAndDimensionKeyOrderByStatTimeDesc(
            String metricType, String metricName, String dimensionKey);

    /** 按小时汇总指定指标名（NODE 在线数等需要按指标名拆分） */
    @Query("select p.statTime, coalesce(sum(p.metricValue), 0), coalesce(sum(p.sampleCount), 0), "
            + "coalesce(sum(p.successCount), 0), coalesce(sum(p.failCount), 0), "
            + "coalesce(sum(p.totalRowCount), 0) from MonMetricPoint p where p.deleted = false "
            + "and p.metricType = :metricType and p.metricName = :metricName "
            + "and p.dimensionType = :dimensionType and p.statTime >= :from and p.statTime < :to "
            + "group by p.statTime order by p.statTime")
    List<Object[]> sumByHourByName(@Param("metricType") String metricType,
                                   @Param("metricName") String metricName,
                                   @Param("dimensionType") String dimensionType,
                                   @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
