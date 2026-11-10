package com.share.ops.monitor.service;

import com.share.ops.file.repository.FlFileRepository;
import com.share.ops.file.repository.FlTransferLogRepository;
import com.share.ops.monitor.entity.MonAlarmRecord;
import com.share.ops.monitor.entity.MonMetricPoint;
import com.share.ops.monitor.entity.MonNode;
import com.share.ops.monitor.repository.MonAlarmRecordRepository;
import com.share.ops.monitor.repository.MonAlarmRuleRepository;
import com.share.ops.monitor.repository.MonMetricPointRepository;
import com.share.ops.monitor.repository.MonNodeRepository;
import com.share.ops.support.CrossDomainReader;
import com.share.ops.support.OpsConstants;
import com.share.ops.support.PageSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 监控服务（FR-12）：平台总览、节点状态（含心跳超时判定）、指标趋势、运维报表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    /** 演示环境组织名称映射（与种子数据/共享组织表保持一致） */
    private static final Map<Long, String> DEPT_NAMES = Map.of(
            1L, "数据中心",
            2L, "财务部",
            3L, "运营管理部",
            4L, "人力资源部",
            9L, "平台运维部");

    private final MonNodeRepository nodeRepository;
    private final MonMetricPointRepository pointRepository;
    private final MonAlarmRecordRepository alarmRecordRepository;
    private final MonAlarmRuleRepository alarmRuleRepository;
    private final FlFileRepository fileRepository;
    private final FlTransferLogRepository transferLogRepository;
    private final CrossDomainReader crossDomain;

    @Value("${share.monitor.heartbeat-timeout-seconds:90}")
    private long heartbeatTimeoutSeconds;

    // ================= 平台总览 =================

    @Transactional
    public Map<String, Object> overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrow = todayStart.plusDays(1);

        Map<String, Object> nodeStat = nodes();
        long nodeTotal = num(nodeStat.get("total"));
        long nodeOnline = num(nodeStat.get("online"));
        long nodeOffline = num(nodeStat.get("offline"));

        // 任务成功率：优先取任务域真实实例，缺失时用指标点兜底
        long taskTotal = 0;
        long taskSuccess = 0;
        long taskFail = 0;
        List<Map<String, Object>> instances = crossDomain.recentTaskInstances(500);
        if (!instances.isEmpty()) {
            for (Map<String, Object> instance : instances) {
                taskTotal++;
                String status = CrossDomainReader.str(instance, "status");
                if (isSuccessStatus(status)) {
                    taskSuccess++;
                } else if (isFailStatus(status)) {
                    taskFail++;
                }
            }
        } else {
            List<Object[]> stat = pointRepository.sumExchangeStat(todayStart.minusDays(7), tomorrow);
            if (!stat.isEmpty() && stat.get(0) != null) {
                taskTotal = ((Number) stat.get(0)[0]).longValue();
                taskSuccess = ((Number) stat.get(0)[1]).longValue();
                taskFail = ((Number) stat.get(0)[2]).longValue();
            }
        }
        double taskSuccessRate = taskTotal == 0 ? 100.0 : round(taskSuccess * 100.0 / taskTotal);

        long todayExchangeRows = nz(pointRepository.sumExchangeRows(todayStart, tomorrow));
        long todayApiCalls = crossDomain.apiCallCount(todayStart, tomorrow);
        if (todayApiCalls == 0) {
            List<Object[]> apiStat = pointRepository.sumApiStat(todayStart, tomorrow);
            if (!apiStat.isEmpty() && apiStat.get(0) != null) {
                todayApiCalls = ((Number) apiStat.get(0)[0]).longValue();
            }
        }
        long openAlarms = alarmRecordRepository.countByStatusAndDeletedFalse(OpsConstants.ALARM_OPEN);

        long successTransfer = transferLogRepository.countByStatusAndDeletedFalse(OpsConstants.TRANSFER_SUCCESS);
        long failTransfer = transferLogRepository.countByStatusAndDeletedFalse(OpsConstants.TRANSFER_FAIL);
        long transferTotal = successTransfer + failTransfer;
        long fileCount = fileRepository.countByDeletedFalse();
        Long fileBytes = fileRepository.sumFileSize();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeTotal", nodeTotal);
        result.put("nodeOnline", nodeOnline);
        result.put("nodeOffline", nodeOffline);
        result.put("nodeOnlineRate", nodeTotal == 0 ? 100.0 : round(nodeOnline * 100.0 / nodeTotal));
        result.put("taskTotal", taskTotal);
        result.put("taskSuccess", taskSuccess);
        result.put("taskFail", taskFail);
        result.put("taskSuccessRate", taskSuccessRate);
        result.put("todayExchangeRows", todayExchangeRows);
        result.put("todayApiCalls", todayApiCalls);
        result.put("openAlarmCount", openAlarms);
        result.put("alarmRuleCount", alarmRuleRepository.countByDeletedFalse());
        result.put("alarmRuleEnabled", alarmRuleRepository.countByEnabledTrueAndDeletedFalse());
        result.put("fileCount", fileCount);
        result.put("totalFileSize", fileBytes == null ? 0L : fileBytes);
        result.put("totalFileSizeText", com.share.ops.file.service.FileService
                .humanSize(fileBytes == null ? 0L : fileBytes));
        result.put("transferTotal", transferTotal);
        result.put("transferSuccess", successTransfer);
        result.put("transferFail", failTransfer);
        result.put("transferSuccessRate", transferTotal == 0 ? 100.0 : round(successTransfer * 100.0 / transferTotal));
        result.put("alarmByLevel", alarmByLevel());
        result.put("recentAlarms", PageSupport.mapList(
                alarmRecordRepository.findTop10ByDeletedFalseOrderByFirstTimeDesc(), this::toAlarmView));
        result.put("recentFailedTasks", recentFailedTasks(instances));
        result.put("nodeList", nodeStat.get("list"));
        result.put("updatedAt", LocalDateTime.now());
        return result;
    }

    // ================= 节点状态 =================

    /**
     * 节点列表：以本域 {@code mon_node} 快照为基础，叠加 share-agent 的 {@code ag_node} 实时状态，
     * 并按 {@code share.monitor.heartbeat-timeout-seconds} 判定心跳超时（超时即离线）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> nodes() {
        LocalDateTime now = LocalDateTime.now();
        List<MonNode> localNodes = nodeRepository.findAllByDeletedFalseOrderByNodeCodeAsc();
        List<Map<String, Object>> agentNodes = crossDomain.agentNodes();
        Map<String, Map<String, Object>> agentByCode = new LinkedHashMap<>();
        for (Map<String, Object> agent : agentNodes) {
            String code = CrossDomainReader.str(agent, "node_code");
            if (StringUtils.hasText(code)) {
                agentByCode.put(code, agent);
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        for (MonNode node : localNodes) {
            Map<String, Object> view = toNodeView(node);
            Map<String, Object> agent = agentByCode.get(node.getNodeCode());
            applyAgentStatus(view, agent);
            judgeHeartbeat(view, now);
            list.add(view);
            covered.add(node.getNodeCode());
        }
        // 前置节点已注册但本域尚无监控快照时，直接用 ag_node 数据补齐
        for (Map.Entry<String, Map<String, Object>> entry : agentByCode.entrySet()) {
            if (covered.contains(entry.getKey())) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            Map<String, Object> agent = entry.getValue();
            view.put("nodeCode", entry.getKey());
            view.put("nodeName", CrossDomainReader.str(agent, "node_name"));
            view.put("nodeType", CrossDomainReader.str(agent, "node_type"));
            view.put("ip", CrossDomainReader.str(agent, "ip"));
            view.put("port", CrossDomainReader.val(agent, "port"));
            view.put("status", CrossDomainReader.str(agent, "status"));
            view.put("cpuUsage", CrossDomainReader.val(agent, "cpu_usage"));
            view.put("memUsage", CrossDomainReader.val(agent, "mem_usage"));
            view.put("diskUsage", CrossDomainReader.val(agent, "disk_usage"));
            view.put("version", CrossDomainReader.str(agent, "version"));
            view.put("lastHeartbeat", CrossDomainReader.time(agent, "last_heartbeat"));
            view.put("source", "AGENT");
            judgeHeartbeat(view, now);
            list.add(view);
        }
        long online = list.stream().filter(n -> OpsConstants.NODE_ONLINE.equals(n.get("status"))).count();
        long offline = list.stream().filter(n -> OpsConstants.NODE_OFFLINE.equals(n.get("status"))).count();
        long degraded = list.stream().filter(n -> OpsConstants.NODE_DEGRADED.equals(n.get("status"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", (long) list.size());
        result.put("online", online);
        result.put("offline", offline);
        result.put("degraded", degraded);
        result.put("heartbeatTimeoutSeconds", heartbeatTimeoutSeconds);
        result.put("updatedAt", LocalDateTime.now());
        return result;
    }

    /**
     * 刷新节点状态到本域快照表（供定时任务调用）：心跳超时 → OFFLINE，心跳正常 → ONLINE。
     */
    @Transactional
    public int refreshNodeStatus() {
        LocalDateTime now = LocalDateTime.now();
        List<MonNode> nodes = nodeRepository.findAllByDeletedFalseOrderByNodeCodeAsc();
        Map<String, Map<String, Object>> agentByCode = new LinkedHashMap<>();
        for (Map<String, Object> agent : crossDomain.agentNodes()) {
            String code = CrossDomainReader.str(agent, "node_code");
            if (StringUtils.hasText(code)) {
                agentByCode.put(code, agent);
            }
        }
        int changed = 0;
        for (MonNode node : nodes) {
            // 演示模拟节点：由平台刷新心跳，保持“在线”演示效果
            if (Boolean.TRUE.equals(node.getSimulated())) {
                String beforeSimulated = node.getStatus();
                node.setLastHeartbeat(now);
                node.setStatus(OpsConstants.NODE_ONLINE);
                if (node.getCpuUsage() != null) {
                    node.setCpuUsage(jitter(node.getCpuUsage(), 3.0));
                }
                if (node.getMemUsage() != null) {
                    node.setMemUsage(jitter(node.getMemUsage(), 2.0));
                }
                if (!java.util.Objects.equals(beforeSimulated, node.getStatus())) {
                    changed++;
                }
                nodeRepository.save(node);
                continue;
            }
            Map<String, Object> agent = agentByCode.get(node.getNodeCode());
            String before = node.getStatus();
            if (agent != null) {
                LocalDateTime heartbeat = CrossDomainReader.time(agent, "last_heartbeat");
                if (heartbeat != null) {
                    node.setLastHeartbeat(heartbeat);
                }
                String agentStatus = CrossDomainReader.str(agent, "status");
                if (StringUtils.hasText(agentStatus)) {
                    node.setStatus(agentStatus);
                }
            }
            boolean timeout = node.getLastHeartbeat() == null
                    || node.getLastHeartbeat().plusSeconds(heartbeatTimeoutSeconds).isBefore(now);
            if (timeout) {
                node.setStatus(OpsConstants.NODE_OFFLINE);
            } else if (!OpsConstants.NODE_DEGRADED.equalsIgnoreCase(node.getStatus())) {
                node.setStatus(OpsConstants.NODE_ONLINE);
            }
            if (!java.util.Objects.equals(before, node.getStatus())) {
                changed++;
            }
            nodeRepository.save(node);
        }
        if (changed > 0) {
            log.info("节点状态刷新：{} 个节点状态发生变化", changed);
        }
        return changed;
    }

    // ================= 指标趋势 =================

    /**
     * 指标趋势：按小时聚合 {@code mon_metric_point}。
     *
     * @param type EXCHANGE / API / NODE
     */
    @Transactional(readOnly = true)
    public Map<String, Object> metrics(String type, LocalDateTime from, LocalDateTime to) {
        String metricType = StringUtils.hasText(type) ? type.toUpperCase() : OpsConstants.METRIC_EXCHANGE;
        LocalDateTime end = to == null ? LocalDateTime.now().plusHours(1) : to;
        LocalDateTime start = from == null ? end.minusHours(24) : from;
        List<Object[]> rows;
        String dimensionType;
        switch (metricType) {
            case OpsConstants.METRIC_API -> {
                dimensionType = OpsConstants.DIM_ALL;
                rows = pointRepository.sumByHour(OpsConstants.METRIC_API, dimensionType, start, end);
            }
            case OpsConstants.METRIC_NODE -> {
                dimensionType = OpsConstants.DIM_ALL;
                rows = pointRepository.sumByHourByName(OpsConstants.METRIC_NODE,
                        OpsConstants.METRIC_NODE_ONLINE, dimensionType, start, end);
            }
            default -> {
                metricType = OpsConstants.METRIC_EXCHANGE;
                dimensionType = OpsConstants.DIM_TASK;
                rows = pointRepository.sumByHour(OpsConstants.METRIC_EXCHANGE, dimensionType, start, end);
            }
        }
        List<Map<String, Object>> points = new ArrayList<>();
        double totalValue = 0;
        long totalSample = 0;
        long totalSuccess = 0;
        long totalFail = 0;
        long totalRow = 0;
        for (Object[] row : rows) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", row[0]);
            double value = ((Number) row[1]).doubleValue();
            long sample = ((Number) row[2]).longValue();
            long success = ((Number) row[3]).longValue();
            long fail = ((Number) row[4]).longValue();
            long rowCount = ((Number) row[5]).longValue();
            point.put("value", value);
            point.put("sampleCount", sample);
            point.put("successCount", success);
            point.put("failCount", fail);
            point.put("rowCount", rowCount);
            point.put("successRate", sample == 0 ? 100.0 : round(success * 100.0 / sample));
            points.add(point);
            totalValue += value;
            totalSample += sample;
            totalSuccess += success;
            totalFail += fail;
            totalRow += rowCount;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("value", round(totalValue));
        summary.put("sampleCount", totalSample);
        summary.put("successCount", totalSuccess);
        summary.put("failCount", totalFail);
        summary.put("rowCount", totalRow);
        summary.put("successRate", totalSample == 0 ? 100.0 : round(totalSuccess * 100.0 / totalSample));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", metricType);
        result.put("dimensionType", dimensionType);
        result.put("from", start);
        result.put("to", end);
        result.put("points", points);
        result.put("summary", summary);
        result.put("nodeGauges", OpsConstants.METRIC_NODE.equals(metricType) ? nodeGauges(end) : null);
        return result;
    }

    /** 节点当前各项指标最新值（NODE 趋势图的辅助数据） */
    private Map<String, Object> nodeGauges(LocalDateTime before) {
        Map<String, Object> gauges = new LinkedHashMap<>();
        for (String metricName : List.of(OpsConstants.METRIC_NODE_ONLINE, OpsConstants.METRIC_NODE_CPU,
                OpsConstants.METRIC_NODE_MEM, OpsConstants.METRIC_NODE_DISK)) {
            List<MonMetricPoint> points = pointRepository
                    .findByMetricTypeAndDimensionTypeAndStatTimeBetweenOrderByStatTimeAsc(
                            OpsConstants.METRIC_NODE, OpsConstants.DIM_ALL, before.minusDays(2), before);
            for (int i = points.size() - 1; i >= 0; i--) {
                MonMetricPoint point = points.get(i);
                if (metricName.equals(point.getMetricName())) {
                    gauges.put(metricName, point.getMetricValue());
                    break;
                }
            }
        }
        return gauges;
    }

    // ================= 运维报表 =================

    /**
     * 运维报表：日/周/月交换量、成功率、部门共享排行、任务健康度、告警与文件传输统计。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> reports(String period) {
        String p = StringUtils.hasText(period) ? period.toLowerCase() : "day";
        int days = switch (p) {
            case "week" -> 7;
            case "month" -> 30;
            default -> 1;
        };
        LocalDateTime to = LocalDateTime.now().plusMinutes(1);
        LocalDateTime from = LocalDate.now().minusDays(days - 1L).atStartOfDay();

        long rows = nz(pointRepository.sumExchangeRows(from, to));
        long batchCount = 0;
        long successCount = 0;
        long failCount = 0;
        long costSum = 0;
        List<Object[]> stat = pointRepository.sumExchangeStat(from, to);
        if (!stat.isEmpty() && stat.get(0) != null) {
            batchCount = ((Number) stat.get(0)[0]).longValue();
            successCount = ((Number) stat.get(0)[1]).longValue();
            failCount = ((Number) stat.get(0)[2]).longValue();
            costSum = ((Number) stat.get(0)[3]).longValue();
        }
        Map<String, Object> exchange = new LinkedHashMap<>();
        exchange.put("rows", rows);
        exchange.put("batchCount", batchCount);
        exchange.put("successCount", successCount);
        exchange.put("failCount", failCount);
        exchange.put("successRate", batchCount == 0 ? 100.0 : round(successCount * 100.0 / batchCount));
        exchange.put("avgCostMs", batchCount == 0 ? 0 : Math.round((double) costSum / batchCount));

        List<Map<String, Object>> deptRanking = new ArrayList<>();
        for (Object[] row : pointRepository.groupExchangeByDimension(OpsConstants.DIM_DEPT, from, to)) {
            long deptId = parseLong(String.valueOf(row[0]));
            long deptRows = ((Number) row[1]).longValue();
            long deptBatches = ((Number) row[2]).longValue();
            long deptSuccess = ((Number) row[3]).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deptId", deptId);
            item.put("deptName", DEPT_NAMES.getOrDefault(deptId, "部门#" + deptId));
            item.put("rowCount", deptRows);
            item.put("batchCount", deptBatches);
            item.put("successCount", deptSuccess);
            item.put("successRate", deptBatches == 0 ? 100.0 : round(deptSuccess * 100.0 / deptBatches));
            deptRanking.add(item);
        }

        Map<Long, String> taskNames = crossDomain.taskNames();
        List<Map<String, Object>> taskHealth = new ArrayList<>();
        for (Object[] row : pointRepository.groupExchangeByDimension(OpsConstants.DIM_TASK, from, to)) {
            long taskId = parseLong(String.valueOf(row[0]));
            long taskBatches = ((Number) row[2]).longValue();
            long taskSuccess = ((Number) row[3]).longValue();
            long taskFail = ((Number) row[4]).longValue();
            double rate = taskBatches == 0 ? 100.0 : round(taskSuccess * 100.0 / taskBatches);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", taskId);
            item.put("taskName", taskNames.getOrDefault(taskId, "任务#" + taskId));
            item.put("rowCount", ((Number) row[1]).longValue());
            item.put("batchCount", taskBatches);
            item.put("successCount", taskSuccess);
            item.put("failCount", taskFail);
            item.put("successRate", rate);
            item.put("healthScore", rate);
            item.put("grade", rate >= 90 ? "A" : (rate >= 70 ? "B" : "C"));
            taskHealth.add(item);
        }
        taskHealth.sort((a, b) -> Double.compare((Double) b.get("healthScore"), (Double) a.get("healthScore")));

        long alarmTotal = 0;
        List<Map<String, Object>> alarmByLevel = new ArrayList<>();
        for (Object[] row : alarmRecordRepository.countByLevelSince(from)) {
            long count = ((Number) row[1]).longValue();
            alarmTotal += count;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("level", row[0]);
            item.put("count", count);
            alarmByLevel.add(item);
        }
        List<Map<String, Object>> alarmTopRules = new ArrayList<>();
        for (Object[] row : alarmRecordRepository.topRules(from)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleName", row[0]);
            item.put("count", ((Number) row[1]).longValue());
            alarmTopRules.add(item);
        }
        Map<String, Object> alarm = new LinkedHashMap<>();
        alarm.put("total", alarmTotal);
        alarm.put("byLevel", alarmByLevel);
        alarm.put("topRules", alarmTopRules);

        long transferSuccess = transferLogRepository
                .countByStatusAndDeletedFalseAndStartTimeAfter(OpsConstants.TRANSFER_SUCCESS, from);
        long transferFail = transferLogRepository
                .countByStatusAndDeletedFalseAndStartTimeAfter(OpsConstants.TRANSFER_FAIL, from);
        long transferTotal = transferSuccess + transferFail;
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("fileCount", fileRepository.countByDeletedFalse());
        file.put("transferTotal", transferTotal);
        file.put("transferSuccess", transferSuccess);
        file.put("transferFail", transferFail);
        file.put("transferSuccessRate", transferTotal == 0 ? 100.0
                : round(transferSuccess * 100.0 / transferTotal));

        Map<String, Object> nodeStat = nodes();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("from", from);
        result.put("to", to);
        result.put("exchange", exchange);
        result.put("deptRanking", deptRanking);
        result.put("deptRankingTotal", (long) deptRanking.size());
        result.put("taskHealth", taskHealth.subList(0, Math.min(taskHealth.size(), 10)));
        result.put("alarm", alarm);
        result.put("file", file);
        result.put("node", Map.of("total", nodeStat.get("total"),
                "online", nodeStat.get("online"), "offline", nodeStat.get("offline")));
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    // ================= 内部方法 =================

    private Map<String, Object> toNodeView(MonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", node.getId());
        m.put("nodeCode", node.getNodeCode());
        m.put("nodeName", node.getNodeName());
        m.put("nodeType", node.getNodeType());
        m.put("ip", node.getIp());
        m.put("port", node.getPort());
        m.put("status", node.getStatus());
        m.put("cpuUsage", node.getCpuUsage());
        m.put("memUsage", node.getMemUsage());
        m.put("diskUsage", node.getDiskUsage());
        m.put("netIn", node.getNetIn());
        m.put("netOut", node.getNetOut());
        m.put("version", node.getVersion());
        m.put("lastHeartbeat", node.getLastHeartbeat());
        m.put("deptId", node.getDeptId());
        m.put("nodeGroup", node.getNodeGroup());
        m.put("runningTasks", node.getRunningTasks());
        m.put("simulated", node.getSimulated());
        m.put("remark", node.getRemark());
        m.put("source", "MON_NODE");
        return m;
    }

    /** 用 ag_node 的实时数据覆盖监控快照 */
    private void applyAgentStatus(Map<String, Object> view, Map<String, Object> agent) {
        if (agent == null) {
            return;
        }
        // 模拟节点以本域快照为准，避免前置节点旧心跳覆盖演示状态
        if (Boolean.TRUE.equals(view.get("simulated"))) {
            view.put("agentRegistered", true);
            return;
        }
        String status = CrossDomainReader.str(agent, "status");
        if (StringUtils.hasText(status)) {
            view.put("status", status);
        }
        LocalDateTime heartbeat = CrossDomainReader.time(agent, "last_heartbeat");
        if (heartbeat != null) {
            view.put("lastHeartbeat", heartbeat);
        }
        Object cpu = CrossDomainReader.val(agent, "cpu_usage");
        if (cpu instanceof Number) {
            view.put("cpuUsage", cpu);
        }
        Object mem = CrossDomainReader.val(agent, "mem_usage");
        if (mem instanceof Number) {
            view.put("memUsage", mem);
        }
        Object disk = CrossDomainReader.val(agent, "disk_usage");
        if (disk instanceof Number) {
            view.put("diskUsage", disk);
        }
        view.put("agentRegistered", true);
    }

    /** 心跳超时判定：超时即离线 */
    private void judgeHeartbeat(Map<String, Object> view, LocalDateTime now) {
        Object heartbeatValue = view.get("lastHeartbeat");
        LocalDateTime heartbeat = null;
        if (heartbeatValue instanceof LocalDateTime ldt) {
            heartbeat = ldt;
        } else if (heartbeatValue != null) {
            try {
                heartbeat = LocalDateTime.parse(String.valueOf(heartbeatValue).replace(' ', 'T'));
            } catch (Exception ignored) {
                heartbeat = null;
            }
        }
        boolean timeout = heartbeat == null
                || heartbeat.plusSeconds(heartbeatTimeoutSeconds).isBefore(now);
        view.put("heartbeatTimeout", timeout);
        if (timeout) {
            view.put("status", OpsConstants.NODE_OFFLINE);
        } else if (!OpsConstants.NODE_DEGRADED.equals(view.get("status"))) {
            view.put("status", OpsConstants.NODE_ONLINE);
        }
    }

    private List<Map<String, Object>> alarmByLevel() {
        LocalDateTime from = LocalDate.now().minusDays(6).atStartOfDay();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : alarmRecordRepository.countByLevelSince(from)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("level", row[0]);
            item.put("count", ((Number) row[1]).longValue());
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> recentFailedTasks(List<Map<String, Object>> instances) {
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Map<String, Object> instance : instances) {
            String status = CrossDomainReader.str(instance, "status");
            if (!isFailStatus(status)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", CrossDomainReader.val(instance, "id"));
            item.put("taskId", CrossDomainReader.val(instance, "task_id"));
            item.put("taskName", CrossDomainReader.str(instance, "task_name"));
            item.put("batchId", CrossDomainReader.str(instance, "batch_id"));
            item.put("status", status);
            item.put("startTime", CrossDomainReader.val(instance, "start_time"));
            item.put("errorMsg", CrossDomainReader.str(instance, "error_msg"));
            failed.add(item);
            if (failed.size() >= 5) {
                break;
            }
        }
        return failed;
    }

    public Map<String, Object> toAlarmView(MonAlarmRecord record) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", record.getId());
        m.put("ruleId", record.getRuleId());
        m.put("ruleName", record.getRuleName());
        m.put("metricType", record.getMetricType());
        m.put("level", record.getAlarmLevel());
        m.put("alarmLevel", record.getAlarmLevel());
        m.put("metricValue", record.getMetricValue());
        m.put("thresholdValue", record.getThresholdValue());
        m.put("status", record.getStatus());
        m.put("objectType", record.getObjectType());
        m.put("objectCode", record.getObjectCode());
        m.put("firstTime", record.getFirstTime());
        m.put("lastTime", record.getLastTime());
        m.put("triggerCount", record.getTriggerCount());
        m.put("notifyStatus", record.getNotifyStatus());
        m.put("ackBy", record.getAckBy());
        m.put("ackTime", record.getAckTime());
        return m;
    }

    private boolean isSuccessStatus(String status) {
        if (status == null) {
            return false;
        }
        String s = status.toUpperCase();
        return s.startsWith("SUCCESS") || s.equals("DONE") || s.equals("COMPLETED") || s.equals("成功");
    }

    private boolean isFailStatus(String status) {
        if (status == null) {
            return false;
        }
        String s = status.toUpperCase();
        return s.startsWith("FAIL") || s.equals("ERROR") || s.equals("失败");
    }

    private long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 指标轻微抖动（模拟节点演示用），限制在 1~99 */
    private double jitter(double base, double amplitude) {
        double delta = (Math.random() - 0.5) * 2 * amplitude;
        double value = base + delta;
        value = Math.max(1.0, Math.min(99.0, value));
        return round(value);
    }

    /** 供定时任务使用：把当前小时的交换量补点（无事件时也能保持趋势连续） */
    @Transactional
    public void warmupHourlyPoints() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime hourStart = now.truncatedTo(ChronoUnit.HOURS);
        List<MonMetricPoint> points = pointRepository
                .findByMetricTypeAndDimensionTypeAndStatTimeBetweenOrderByStatTimeAsc(
                        OpsConstants.METRIC_EXCHANGE, OpsConstants.DIM_TASK, hourStart, now.plusSeconds(1));
        if (points.isEmpty()) {
            log.debug("当前小时 {} 暂无交换事件指标点", hourStart);
        }
    }
}
