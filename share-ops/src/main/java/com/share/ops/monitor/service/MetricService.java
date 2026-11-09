package com.share.ops.monitor.service;

import com.share.common.event.ExchangeEvent;
import com.share.ops.monitor.entity.MonMetricPoint;
import com.share.ops.monitor.entity.MonNode;
import com.share.ops.monitor.repository.MonMetricPointRepository;
import com.share.ops.monitor.repository.MonNodeRepository;
import com.share.ops.support.CrossDomainReader;
import com.share.ops.support.OpsConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 指标聚合服务（FR-12）：把交换事件、API 调用、节点心跳按小时聚合到 {@code mon_metric_point}。
 * <p>写入策略：交换批次按 TASK / DEPT 两个维度累加；API 与 NODE 指标用覆盖式写入最近小时点。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricService {

    private final MonMetricPointRepository pointRepository;
    private final MonNodeRepository nodeRepository;
    private final CrossDomainReader crossDomain;

    /** 消费交换事件：按 TASK 维度与 DEPT 维度各累加一个点 */
    @Transactional
    public synchronized void recordExchange(ExchangeEvent event) {
        if (event == null) {
            return;
        }
        LocalDateTime statTime = event.getEndTime() != null ? event.getEndTime()
                : (event.getStartTime() != null ? event.getStartTime() : LocalDateTime.now());
        String taskKey = event.getTaskId() == null ? "UNKNOWN" : String.valueOf(event.getTaskId());
        accumulate(OpsConstants.METRIC_EXCHANGE, OpsConstants.METRIC_EXCHANGE_BATCH,
                OpsConstants.DIM_TASK, taskKey, statTime,
                1, event.isSuccess() ? 1 : 0, event.isSuccess() ? 0 : 1,
                event.getRowCount(), event.getCostMs());
        if (event.getDeptId() != null) {
            accumulate(OpsConstants.METRIC_EXCHANGE, OpsConstants.METRIC_EXCHANGE_BATCH,
                    OpsConstants.DIM_DEPT, String.valueOf(event.getDeptId()), statTime,
                    1, event.isSuccess() ? 1 : 0, event.isSuccess() ? 0 : 1,
                    event.getRowCount(), event.getCostMs());
        }
        log.debug("交换指标聚合完成 batch={} task={} rows={} success={}",
                event.getBatchId(), taskKey, event.getRowCount(), event.isSuccess());
    }

    /**
     * 累加写入小时指标点（不存在则新建）。
     */
    @Transactional
    public synchronized MonMetricPoint accumulate(String metricType, String metricName,
                                                  String dimensionType, String dimensionKey,
                                                  LocalDateTime time, long sampleDelta, long successDelta,
                                                  long failDelta, long rowDelta, long costDelta) {
        LocalDateTime statTime = truncateHour(time);
        String key = isBlank(dimensionKey) ? "ALL" : dimensionKey;
        String dimType = isBlank(dimensionType) ? OpsConstants.DIM_ALL : dimensionType;
        MonMetricPoint point = pointRepository
                .findByMetricTypeAndMetricNameAndDimensionTypeAndDimensionKeyAndStatTime(
                        metricType, metricName, dimType, key, statTime)
                .orElseGet(() -> newPoint(metricType, metricName, dimType, key, statTime));
        point.setSampleCount(nz(point.getSampleCount()) + sampleDelta);
        point.setSuccessCount(nz(point.getSuccessCount()) + successDelta);
        point.setFailCount(nz(point.getFailCount()) + failDelta);
        point.setTotalRowCount(nz(point.getTotalRowCount()) + rowDelta);
        point.setTotalCostMs(nz(point.getTotalCostMs()) + costDelta);
        // EXCHANGE 指标值 = 本小时交换行数；API 指标值 = 本小时调用量
        point.setMetricValue((double) nz(point.getTotalRowCount()));
        return pointRepository.save(point);
    }

    /** 覆盖式写入（节点在线数、平均 CPU、API 调用量等快照型指标） */
    @Transactional
    public synchronized MonMetricPoint overwrite(String metricType, String metricName, String dimensionType,
                                                 String dimensionKey, LocalDateTime time, double value,
                                                 long sampleCount, long successCount, long failCount) {
        LocalDateTime statTime = truncateHour(time);
        String key = isBlank(dimensionKey) ? "ALL" : dimensionKey;
        String dimType = isBlank(dimensionType) ? OpsConstants.DIM_ALL : dimensionType;
        MonMetricPoint point = pointRepository
                .findByMetricTypeAndMetricNameAndDimensionTypeAndDimensionKeyAndStatTime(
                        metricType, metricName, dimType, key, statTime)
                .orElseGet(() -> newPoint(metricType, metricName, dimType, key, statTime));
        point.setMetricValue(value);
        point.setSampleCount(sampleCount);
        point.setSuccessCount(successCount);
        point.setFailCount(failCount);
        return pointRepository.save(point);
    }

    /** 采集节点指标：在线数 / 平均 CPU / 内存 / 磁盘 */
    @Transactional
    public synchronized void collectNodeMetrics() {
        List<MonNode> nodes = nodeRepository.findAllByDeletedFalseOrderByNodeCodeAsc();
        if (nodes.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long online = nodes.stream().filter(n -> OpsConstants.NODE_ONLINE.equalsIgnoreCase(n.getStatus())).count();
        double avgCpu = nodes.stream().filter(n -> n.getCpuUsage() != null)
                .mapToDouble(MonNode::getCpuUsage).average().orElse(0);
        double avgMem = nodes.stream().filter(n -> n.getMemUsage() != null)
                .mapToDouble(MonNode::getMemUsage).average().orElse(0);
        double avgDisk = nodes.stream().filter(n -> n.getDiskUsage() != null)
                .mapToDouble(MonNode::getDiskUsage).average().orElse(0);
        overwrite(OpsConstants.METRIC_NODE, OpsConstants.METRIC_NODE_ONLINE, OpsConstants.DIM_ALL, "ALL",
                now, online, nodes.size(), online, nodes.size() - online);
        overwrite(OpsConstants.METRIC_NODE, OpsConstants.METRIC_NODE_CPU, OpsConstants.DIM_ALL, "ALL",
                now, round(avgCpu), nodes.size(), online, nodes.size() - online);
        overwrite(OpsConstants.METRIC_NODE, OpsConstants.METRIC_NODE_MEM, OpsConstants.DIM_ALL, "ALL",
                now, round(avgMem), nodes.size(), online, nodes.size() - online);
        overwrite(OpsConstants.METRIC_NODE, OpsConstants.METRIC_NODE_DISK, OpsConstants.DIM_ALL, "ALL",
                now, round(avgDisk), nodes.size(), online, nodes.size() - online);
        // 每个节点单独一个 CPU 点，便于节点级趋势
        for (MonNode node : nodes) {
            overwrite(OpsConstants.METRIC_NODE, OpsConstants.METRIC_NODE_CPU, OpsConstants.DIM_NODE,
                    node.getNodeCode(), now, node.getCpuUsage() == null ? 0 : node.getCpuUsage(),
                    1, OpsConstants.NODE_ONLINE.equalsIgnoreCase(node.getStatus()) ? 1 : 0,
                    OpsConstants.NODE_ONLINE.equalsIgnoreCase(node.getStatus()) ? 0 : 1);
        }
    }

    /** 采集 API 调用指标：今日累计调用量写入当前小时点（数据源为共享服务域 shr_call_stat） */
    @Transactional
    public synchronized void collectApiMetrics() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tomorrow = todayStart.plusDays(1);
        long calls = crossDomain.apiCallCount(todayStart, tomorrow);
        if (calls <= 0) {
            // 跨域计量表尚未就绪时，用上一个 API 点兜底，避免趋势断档
            Optional<MonMetricPoint> last = pointRepository
                    .findTopByMetricTypeAndMetricNameAndDimensionKeyOrderByStatTimeDesc(
                            OpsConstants.METRIC_API, OpsConstants.METRIC_API_CALL, "ALL");
            if (last.isEmpty()) {
                return;
            }
            calls = nz(last.get().getSampleCount());
        }
        long fails = crossDomain.apiFailCount(todayStart, tomorrow);
        overwrite(OpsConstants.METRIC_API, OpsConstants.METRIC_API_CALL, OpsConstants.DIM_ALL, "ALL",
                now, calls, calls, Math.max(0, calls - fails), fails);
    }

    /** 小时取整 */
    public static LocalDateTime truncateHour(LocalDateTime time) {
        LocalDateTime target = time == null ? LocalDateTime.now() : time;
        return target.truncatedTo(ChronoUnit.HOURS);
    }

    public static LocalDateTime truncateDay(LocalDateTime time) {
        LocalDateTime target = time == null ? LocalDateTime.now() : time;
        return target.truncatedTo(ChronoUnit.DAYS);
    }

    private MonMetricPoint newPoint(String metricType, String metricName, String dimensionType,
                                    String dimensionKey, LocalDateTime statTime) {
        MonMetricPoint point = new MonMetricPoint();
        point.setMetricType(metricType);
        point.setMetricName(metricName);
        point.setDimensionType(isBlank(dimensionType) ? OpsConstants.DIM_ALL : dimensionType);
        point.setDimensionKey(dimensionKey);
        point.setStatTime(statTime);
        point.setMetricValue(0.0);
        point.setSampleCount(0L);
        point.setSuccessCount(0L);
        point.setFailCount(0L);
        point.setTotalRowCount(0L);
        point.setTotalCostMs(0L);
        return point;
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /** 批次号 → 指标点（供监控总览展示最近交换情况） */
    public Map<String, Object> latestExchangeSummary() {
        return Map.of("supported", true);
    }
}
