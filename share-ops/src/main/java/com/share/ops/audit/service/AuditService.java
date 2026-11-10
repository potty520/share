package com.share.ops.audit.service;

import com.share.common.api.ErrorCode;
import com.share.common.audit.AuditEvent;
import com.share.common.exception.BizException;
import com.share.ops.audit.entity.AudOperationLog;
import com.share.ops.audit.repository.AudOperationLogRepository;
import com.share.ops.support.CrossDomainReader;
import com.share.ops.support.OpsConstants;
import com.share.ops.support.PageSupport;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 安全审计服务（FR-11）：审计事件入库、检索、统计、数据流转追踪、异常行为识别、CSV 报表导出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AudOperationLogRepository logRepository;
    private final CrossDomainReader crossDomain;
    private final ElasticsearchAuditService elasticsearchAuditService;

    @Value("${share.monitor.audit-retention-days:180}")
    private int defaultRetentionDays;

    @Value("${share.monitor.audit-archive-dir:./data/audit-archive}")
    private String archiveDir;

    // ================= 事件入库 =================

    /**
     * 审计事件入库。使用独立事务（REQUIRES_NEW），保证业务事务回滚时审计记录不丢。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AudOperationLog record(AuditEvent event) {
        AudOperationLog entity = new AudOperationLog();
        entity.setOperatorId(event.getOperatorId());
        entity.setOperatorName(defaultIfBlank(event.getOperatorName(), "anonymous"));
        entity.setRoleCode(event.getRoleCode());
        entity.setModule(defaultIfBlank(event.getModule(), "unknown"));
        entity.setAction(defaultIfBlank(event.getAction(), "UNKNOWN"));
        entity.setTargetType(event.getTargetType());
        entity.setTargetId(truncate(event.getTargetId(), 128));
        entity.setDetail(truncate(event.getDetail(), 4000));
        entity.setResult(defaultIfBlank(event.getResult(), OpsConstants.RESULT_SUCCESS));
        entity.setErrorMsg(truncate(event.getErrorMsg(), 1000));
        entity.setIp(event.getIp());
        entity.setUserAgent(truncate(event.getUserAgent(), 400));
        entity.setRequestMethod(event.getMethod());
        entity.setUri(event.getUri());
        entity.setCostMs(event.getCostMs());
        entity.setTraceId(event.getTraceId());
        entity.setOperateTime(event.getOperateTime() == null ? LocalDateTime.now() : event.getOperateTime());
        AudOperationLog saved = logRepository.save(entity);
        elasticsearchAuditService.index(saved);
        return saved;
    }

    /** 供种子数据/系统内部直接补录审计日志 */
    @Transactional
    public AudOperationLog save(AudOperationLog entity) {
        return logRepository.save(entity);
    }

    /** 按保留天数逻辑删除过期审计日志，返回清理条数。 */
    @Transactional
    public Map<String, Object> cleanupExpired(Integer days) {
        return cleanupExpired(days, "LOGICAL");
    }

    /**
     * 按保留天数清理过期审计日志。
     * <p>mode=LOGICAL 逻辑删除（默认，可检索追溯）；ARCHIVE 先导出 CSV 归档再做物理删除；
     * PHYSICAL 直接物理删除（满足审计留存合规的物理清理要求）。</p>
     */
    @Transactional
    public Map<String, Object> cleanupExpired(Integer days, String mode) {
        int retention = days == null || days <= 0 ? defaultRetentionDays : Math.min(days, 3650);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retention));
        List<AudOperationLog> expired = logRepository.findByDeletedFalseAndOperateTimeBefore(cutoff);
        String normalized = mode == null || mode.isBlank() ? "LOGICAL" : mode.trim().toUpperCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retentionDays", retention);
        result.put("cutoff", cutoff);
        result.put("matched", expired.size());
        result.put("mode", normalized);
        if ("PHYSICAL".equals(normalized) || "ARCHIVE".equals(normalized)) {
            if ("ARCHIVE".equals(normalized) && !expired.isEmpty()) {
                result.put("archiveFile", writeArchive(expired));
            }
            logRepository.deleteAll(expired);
            result.put("deleted", expired.size());
            result.put("physical", true);
            log.info("审计日志物理清理完成：保留 {} 天，截止 {}，删除 {} 条，模式 {}", retention, cutoff,
                    expired.size(), normalized);
            return result;
        }
        for (AudOperationLog entry : expired) {
            entry.setDeleted(true);
        }
        if (!expired.isEmpty()) {
            logRepository.saveAll(expired);
        }
        result.put("deleted", expired.size());
        result.put("physical", false);
        log.info("审计日志保留期清理完成：保留 {} 天，截止 {}，清理 {} 条", retention, cutoff, expired.size());
        return result;
    }

    /** 导出过期日志为 CSV 归档文件，返回绝对路径。 */
    private String writeArchive(List<AudOperationLog> rows) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(archiveDir).toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(dir);
            String fileName = "audit-archive-" + LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
            java.nio.file.Path file = dir.resolve(fileName);
            StringBuilder sb = new StringBuilder();
            sb.append("id,operatorName,roleCode,module,action,targetType,targetId,result,ip,uri,costMs,operateTime,detail\n");
            for (AudOperationLog l : rows) {
                sb.append(csvCell(l.getId() == null ? null : String.valueOf(l.getId()))).append(',')
                        .append(csvCell(l.getOperatorName())).append(',')
                        .append(csvCell(l.getRoleCode())).append(',')
                        .append(csvCell(l.getModule())).append(',')
                        .append(csvCell(l.getAction())).append(',')
                        .append(csvCell(l.getTargetType())).append(',')
                        .append(csvCell(l.getTargetId())).append(',')
                        .append(csvCell(l.getResult())).append(',')
                        .append(csvCell(l.getIp())).append(',')
                        .append(csvCell(l.getUri())).append(',')
                        .append(csvCell(l.getCostMs() == null ? null : String.valueOf(l.getCostMs()))).append(',')
                        .append(csvCell(l.getOperateTime() == null ? null : l.getOperateTime().toString())).append(',')
                        .append(csvCell(l.getDetail())).append('\n');
            }
            java.nio.file.Files.writeString(file, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            return file.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "审计归档文件写入失败: " + e.getMessage());
        }
    }

    // ================= 检索 =================

    @Transactional(readOnly = true)
    public Map<String, Object> page(String operator, String action, String module, String result,
                                    String keyword, Long operatorId, Long minCostMs,
                                    LocalDateTime from, LocalDateTime to,
                                    Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        if (elasticsearchAuditService.available()) {
            ElasticsearchAuditService.SearchResult search = elasticsearchAuditService.search(
                    operator, action, module, result, keyword, operatorId, minCostMs, from, to, pn, ps);
            if (!search.fallback()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("list", search.items());
                body.put("total", search.total());
                body.put("pageNum", pn);
                body.put("pageSize", ps);
                body.put("pages", (search.total() + ps - 1) / ps);
                body.put("backend", "ELASTICSEARCH");
                return body;
            }
        }
        Specification<AudOperationLog> spec = buildSpec(operator, action, module, result, keyword,
                operatorId, minCostMs, from, to);
        Page<AudOperationLog> page = logRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "operateTime")));
        Map<String, Object> body = PageSupport.of(page, this::toView);
        body.put("backend", "MYSQL");
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        AudOperationLog entity = find(id);
        Map<String, Object> view = toView(entity);
        view.put("detail", entity.getDetail());
        view.put("errorMsg", entity.getErrorMsg());
        view.put("userAgent", entity.getUserAgent());
        view.put("traceId", entity.getTraceId());
        return view;
    }

    @Transactional(readOnly = true)
    public AudOperationLog find(Long id) {
        return logRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审计日志不存在: " + id));
    }

    // ================= 统计 =================

    @Transactional(readOnly = true)
    public Map<String, Object> stats(Integer days) {
        int window = days == null || days <= 0 ? 14 : Math.min(days, 90);
        long total = logRepository.countByDeletedFalse();
        long fail = logRepository.countByResultAndDeletedFalse(OpsConstants.RESULT_FAIL);
        double failRate = total == 0 ? 0.0 : Math.round(fail * 10000.0 / total) / 100.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("failCount", fail);
        result.put("failRate", failRate);
        result.put("byModule", pairs(logRepository.countByModule(), 20));
        result.put("byAction", pairs(logRepository.countByAction(), 20));
        result.put("byResult", pairs(logRepository.countByResult(), 10));
        result.put("topOperators", pairs(logRepository.countByOperator(), 10));

        // 日趋势（含无数据日期补 0，前端图表可直接渲染）
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(window - 1L);
        Map<LocalDate, Long> trend = new TreeMap<>();
        for (int i = 0; i < window; i++) {
            trend.put(startDate.plusDays(i), 0L);
        }
        LocalDateTime from = startDate.atStartOfDay();
        List<AudOperationLog> recent = logRepository
                .findByDeletedFalseAndOperateTimeAfterOrderByOperateTimeAsc(from);
        long offHours = 0;
        for (AudOperationLog l : recent) {
            if (l.getOperateTime() != null) {
                LocalDate d = l.getOperateTime().toLocalDate();
                trend.computeIfPresent(d, (k, v) -> v + 1);
                int hour = l.getOperateTime().getHour();
                if (hour >= 22 || hour < 6) {
                    offHours++;
                }
            }
        }
        List<Map<String, Object>> trendList = new ArrayList<>();
        trend.forEach((date, count) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("count", count);
            trendList.add(item);
        });
        result.put("dailyTrend", trendList);
        result.put("windowDays", window);
        result.put("windowTotal", (long) recent.size());
        result.put("offHoursCount", offHours);
        result.put("todayCount", trend.getOrDefault(today, 0L));
        return result;
    }

    // ================= 数据流转追踪 =================

    /**
     * 数据流转追踪：{@code batchId} 串联 源端 → 交换任务 → 目标端。
     * <p>跨域部分（sw_batch / sw_transfer_log / tk_task_instance）走只读 JdbcTemplate，缺表时安全降级。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trace(String batchId) {
        if (!StringUtils.hasText(batchId)) {
            throw new BizException(ErrorCode.AUDIT_QUERY_ILLEGAL, "batchId 不能为空");
        }
        String key = batchId.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", key);

        Map<String, Object> batch = crossDomain.batch(key);
        Map<String, Object> instance = crossDomain.taskInstanceByBatch(key);
        List<Map<String, Object>> transferLogs = crossDomain.swTransferLogs(key);
        List<Map<String, Object>> auditLogs = PageSupport.mapList(
                logRepository.findForTrace(key), this::toView);

        result.put("batch", batch);
        result.put("instance", instance);
        result.put("transferLogs", transferLogs);
        result.put("auditLogs", auditLogs);
        result.put("chain", buildChain(key, batch, instance, transferLogs));
        result.put("traceAvailable", batch != null || instance != null
                || !transferLogs.isEmpty() || !auditLogs.isEmpty());
        return result;
    }

    /** 组装源端 → 任务 → 目标端 链路节点 */
    private List<Map<String, Object>> buildChain(String batchId, Map<String, Object> batch,
                                                 Map<String, Object> instance,
                                                 List<Map<String, Object>> transferLogs) {
        List<Map<String, Object>> chain = new ArrayList<>();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("stage", "源端");
        source.put("stageCode", "SOURCE");
        source.put("sourceType", firstNonNull(
                CrossDomainReader.str(batch, "source_type"),
                CrossDomainReader.str(instance, "source_type"),
                CrossDomainReader.str(transferLogs.isEmpty() ? null : transferLogs.get(0), "source_type")));
        source.put("sourceId", firstNonNull(
                CrossDomainReader.str(batch, "source_id"),
                CrossDomainReader.str(transferLogs.isEmpty() ? null : transferLogs.get(0), "source_id")));
        source.put("description", "数据从源数据源/文件读取");
        chain.add(source);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("stage", "交换任务");
        task.put("stageCode", "TASK");
        task.put("batchId", batchId);
        task.put("taskId", firstNonNull(
                CrossDomainReader.str(batch, "task_id"),
                CrossDomainReader.str(instance, "task_id")));
        task.put("taskName", firstNonNull(
                CrossDomainReader.str(batch, "task_name"),
                CrossDomainReader.str(instance, "task_name")));
        task.put("status", firstNonNull(
                CrossDomainReader.str(batch, "status"),
                CrossDomainReader.str(instance, "status")));
        task.put("rowCount", firstNonNull(
                CrossDomainReader.str(batch, "row_count"),
                CrossDomainReader.str(instance, "row_count")));
        task.put("checksum", CrossDomainReader.str(batch, "checksum"));
        task.put("startTime", firstNonNull(
                CrossDomainReader.str(batch, "start_time"),
                CrossDomainReader.str(instance, "start_time")));
        task.put("endTime", firstNonNull(
                CrossDomainReader.str(batch, "end_time"),
                CrossDomainReader.str(instance, "end_time")));
        chain.add(task);

        for (Map<String, Object> transferLog : transferLogs) {
            Map<String, Object> hop = new LinkedHashMap<>();
            hop.put("stage", "流转");
            hop.put("stageCode", "TRANSFER");
            hop.put("sourceType", CrossDomainReader.str(transferLog, "source_type"));
            hop.put("sourceId", CrossDomainReader.str(transferLog, "source_id"));
            hop.put("targetType", CrossDomainReader.str(transferLog, "target_type"));
            hop.put("targetId", CrossDomainReader.str(transferLog, "target_id"));
            hop.put("rowCount", CrossDomainReader.str(transferLog, "row_count"));
            hop.put("status", CrossDomainReader.str(transferLog, "status"));
            hop.put("message", CrossDomainReader.str(transferLog, "message"));
            hop.put("createTime", CrossDomainReader.str(transferLog, "create_time"));
            chain.add(hop);
        }

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("stage", "目标端");
        target.put("stageCode", "TARGET");
        target.put("targetType", firstNonNull(
                CrossDomainReader.str(batch, "target_type"),
                CrossDomainReader.str(instance, "target_type"),
                CrossDomainReader.str(transferLogs.isEmpty() ? null : transferLogs.get(transferLogs.size() - 1),
                        "target_type")));
        target.put("targetId", firstNonNull(
                CrossDomainReader.str(batch, "target_id"),
                CrossDomainReader.str(transferLogs.isEmpty() ? null : transferLogs.get(transferLogs.size() - 1),
                        "target_id")));
        target.put("description", "数据写入目标端/文件区");
        chain.add(target);
        return chain;
    }

    // ================= 异常行为识别 =================

    /**
     * 异常行为规则（近 7 天）：
     * <ol>
     *   <li>非工作时间（22:00~06:00）操作；</li>
     *   <li>单用户在 5 分钟窗口内操作超过 20 次；</li>
     *   <li>同一用户 result=FAIL 连续超过 5 次。</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> anomalies() {
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        List<AudOperationLog> logs = logRepository
                .findByDeletedFalseAndOperateTimeAfterOrderByOperateTimeAsc(from);
        List<Map<String, Object>> items = new ArrayList<>();

        // 规则 1：非工作时间操作
        List<AudOperationLog> offHours = new ArrayList<>();
        for (AudOperationLog l : logs) {
            if (l.getOperateTime() == null) {
                continue;
            }
            int hour = l.getOperateTime().getHour();
            if (hour >= 22 || hour < 6) {
                offHours.add(l);
            }
        }
        if (!offHours.isEmpty()) {
            items.add(anomaly("OFF_HOURS", "P2", "非工作时间操作",
                    "22:00~06:00 期间发生 " + offHours.size() + " 次操作，存在越权或违规操作风险",
                    null, offHours));
        }

        // 规则 2：5 分钟内高频操作
        for (Map.Entry<String, List<AudOperationLog>> entry : groupByOperator(logs).entrySet()) {
            List<AudOperationLog> list = entry.getValue();
            int bestCount = 0;
            int bestStart = 0;
            for (int i = 0; i < list.size(); i++) {
                int j = i;
                while (j < list.size() && Duration.between(list.get(i).getOperateTime(),
                        list.get(j).getOperateTime()).getSeconds() <= 300) {
                    j++;
                }
                if (j - i > bestCount) {
                    bestCount = j - i;
                    bestStart = i;
                }
            }
            if (bestCount > 20) {
                items.add(anomaly("HIGH_FREQUENCY", "P1", "单用户短时高频操作",
                        "用户 " + entry.getKey() + " 在 5 分钟内操作 " + bestCount + " 次，疑似批量导出或脚本调用",
                        entry.getKey(), list.subList(bestStart, Math.min(list.size(), bestStart + bestCount))));
            }
        }

        // 规则 3：连续失败
        for (Map.Entry<String, List<AudOperationLog>> entry : groupByOperator(logs).entrySet()) {
            List<AudOperationLog> list = entry.getValue();
            int current = 0;
            int maxRun = 0;
            int endIndex = -1;
            for (int i = 0; i < list.size(); i++) {
                if (OpsConstants.RESULT_FAIL.equalsIgnoreCase(list.get(i).getResult())) {
                    current++;
                    if (current > maxRun) {
                        maxRun = current;
                        endIndex = i;
                    }
                } else {
                    current = 0;
                }
            }
            if (maxRun > 5) {
                int fromIndex = Math.max(0, endIndex - maxRun + 1);
                items.add(anomaly("CONSECUTIVE_FAIL", "P1", "连续操作失败",
                        "用户 " + entry.getKey() + " 连续 " + maxRun + " 次操作失败，疑似越权尝试或系统异常",
                        entry.getKey(), list.subList(fromIndex, endIndex + 1)));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", "近 7 天");
        result.put("from", from);
        result.put("total", items.size());
        result.put("items", items);
        result.put("rules", List.of(
                Map.of("code", "OFF_HOURS", "name", "非工作时间操作", "expression", "22:00 ≤ 操作时间 或 操作时间 < 06:00"),
                Map.of("code", "HIGH_FREQUENCY", "name", "单用户短时高频", "expression", "同一用户 5 分钟内操作 > 20 次"),
                Map.of("code", "CONSECUTIVE_FAIL", "name", "连续操作失败", "expression", "同一用户 result=FAIL 连续 > 5 次")));
        return result;
    }

    // ================= CSV 导出 =================

    /** 审计报表导出（CSV，按检索条件） */
    @Transactional(readOnly = true)
    public String exportCsv(String operator, String action, String module, String result,
                            String keyword, Long operatorId, Long minCostMs,
                            LocalDateTime from, LocalDateTime to) {
        Specification<AudOperationLog> spec = buildSpec(operator, action, module, result, keyword,
                operatorId, minCostMs, from, to);
        List<AudOperationLog> logs = logRepository.findAll(spec,
                PageRequest.of(0, 5000, Sort.by(Sort.Direction.DESC, "operateTime"))).getContent();
        StringBuilder csv = new StringBuilder();
        csv.append("操作时间,操作人,角色,模块,动作,对象类型,对象ID,结果,IP,请求方法,URI,耗时(ms),详情\r\n");
        for (AudOperationLog l : logs) {
            csv.append(csvCell(l.getOperateTime() == null ? "" : l.getOperateTime().toString())).append(',')
                    .append(csvCell(l.getOperatorName())).append(',')
                    .append(csvCell(l.getRoleCode())).append(',')
                    .append(csvCell(l.getModule())).append(',')
                    .append(csvCell(l.getAction())).append(',')
                    .append(csvCell(l.getTargetType())).append(',')
                    .append(csvCell(l.getTargetId())).append(',')
                    .append(csvCell(l.getResult())).append(',')
                    .append(csvCell(l.getIp())).append(',')
                    .append(csvCell(l.getRequestMethod())).append(',')
                    .append(csvCell(l.getUri())).append(',')
                    .append(l.getCostMs() == null ? "" : l.getCostMs()).append(',')
                    .append(csvCell(l.getDetail())).append("\r\n");
        }
        return csv.toString();
    }

    // ================= 内部方法 =================

    private Specification<AudOperationLog> buildSpec(String operator, String action, String module,
                                                     String result, String keyword, Long operatorId,
                                                     Long minCostMs, LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(operator)) {
                String kw = "%" + operator.trim().toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(root.get("operatorName")), kw));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (StringUtils.hasText(module)) {
                predicates.add(cb.equal(root.get("module"), module));
            }
            if (StringUtils.hasText(result)) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            if (operatorId != null) {
                predicates.add(cb.equal(root.get("operatorId"), operatorId));
            }
            if (minCostMs != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("costMs"), minCostMs));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("action")), kw),
                        cb.like(cb.lower(root.get("module")), kw),
                        cb.like(cb.lower(root.get("targetId")), kw),
                        cb.like(cb.lower(root.get("detail")), kw),
                        cb.like(cb.lower(root.get("uri")), kw)));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("operateTime"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("operateTime"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 按操作人分组（时间升序，供窗口分析） */
    private Map<String, List<AudOperationLog>> groupByOperator(List<AudOperationLog> logs) {
        Map<String, List<AudOperationLog>> grouped = new LinkedHashMap<>();
        for (AudOperationLog l : logs) {
            if (l.getOperateTime() == null) {
                continue;
            }
            String operator = defaultIfBlank(l.getOperatorName(), "anonymous");
            grouped.computeIfAbsent(operator, k -> new ArrayList<>()).add(l);
        }
        grouped.values().forEach(list -> list.sort(Comparator.comparing(AudOperationLog::getOperateTime)));
        return grouped;
    }

    private Map<String, Object> anomaly(String type, String level, String title, String description,
                                        String operatorName, List<AudOperationLog> samples) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("level", level);
        item.put("title", title);
        item.put("description", description);
        item.put("operatorName", operatorName);
        item.put("count", samples.size());
        Set<String> operators = new LinkedHashSet<>();
        LocalDateTime first = null;
        LocalDateTime last = null;
        for (AudOperationLog l : samples) {
            if (l.getOperatorName() != null) {
                operators.add(l.getOperatorName());
            }
            if (l.getOperateTime() != null) {
                if (first == null || l.getOperateTime().isBefore(first)) {
                    first = l.getOperateTime();
                }
                if (last == null || l.getOperateTime().isAfter(last)) {
                    last = l.getOperateTime();
                }
            }
        }
        item.put("operators", new ArrayList<>(operators));
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", first);
        range.put("to", last);
        item.put("timeRange", range);
        List<Map<String, Object>> sampleViews = new ArrayList<>();
        for (int i = 0; i < Math.min(samples.size(), 5); i++) {
            sampleViews.add(toView(samples.get(i)));
        }
        item.put("samples", sampleViews);
        return item;
    }

    private List<Map<String, Object>> pairs(List<Object[]> rows, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row[0] == null ? "未分类" : String.valueOf(row[0]));
            item.put("value", row[1] instanceof Number n ? n.longValue() : 0L);
            list.add(item);
            if (list.size() >= limit) {
                break;
            }
        }
        return list;
    }

    public Map<String, Object> toView(AudOperationLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("operatorId", l.getOperatorId());
        m.put("operatorName", l.getOperatorName());
        m.put("roleCode", l.getRoleCode());
        m.put("module", l.getModule());
        m.put("action", l.getAction());
        m.put("targetType", l.getTargetType());
        m.put("targetId", l.getTargetId());
        m.put("result", l.getResult());
        m.put("ip", l.getIp());
        m.put("method", l.getRequestMethod());
        m.put("uri", l.getUri());
        m.put("costMs", l.getCostMs());
        m.put("operateTime", l.getOperateTime());
        m.put("createTime", l.getCreateTime());
        m.put("detailPreview", truncate(l.getDetail(), 200));
        return m;
    }

    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private String defaultIfBlank(String v, String fallback) {
        return StringUtils.hasText(v) ? v : fallback;
    }

    private String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max) + "...";
    }

    private String firstNonNull(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }
}
