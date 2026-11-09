package com.share.ops.monitor.service;

import com.share.common.api.ErrorCode;
import com.share.common.event.NotifyEvent;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import com.share.ops.monitor.entity.MonAlarmRecord;
import com.share.ops.monitor.entity.MonAlarmRule;
import com.share.ops.monitor.entity.MonNode;
import com.share.ops.monitor.repository.MonAlarmRecordRepository;
import com.share.ops.monitor.repository.MonAlarmRuleRepository;
import com.share.ops.monitor.repository.MonMetricPointRepository;
import com.share.ops.monitor.repository.MonNodeRepository;
import com.share.ops.support.CrossDomainReader;
import com.share.ops.support.OpsConstants;
import com.share.ops.support.PageSupport;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控告警服务（FR-12）：告警规则 CRUD、规则评估（任务失败率/节点离线/API 错误率）、告警确认与统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmService {

    private final MonAlarmRuleRepository ruleRepository;
    private final MonAlarmRecordRepository recordRepository;
    private final MonMetricPointRepository pointRepository;
    private final MonNodeRepository nodeRepository;
    private final CrossDomainReader crossDomain;
    private final ApplicationEventPublisher publisher;

    // ================= 规则 CRUD =================

    @Transactional(readOnly = true)
    public Map<String, Object> pageRules(String keyword, String metricType, Boolean enabled,
                                         Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        Specification<MonAlarmRule> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("ruleName")), kw),
                        cb.like(cb.lower(root.get("metricType")), kw)));
            }
            if (StringUtils.hasText(metricType)) {
                predicates.add(cb.equal(root.get("metricType"), metricType));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<MonAlarmRule> page = ruleRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "createTime")));
        return PageSupport.of(page, this::toRuleView);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRules(String metricType, Boolean enabled) {
        List<MonAlarmRule> rules = ruleRepository.findAllByDeletedFalseOrderByCreateTimeDesc();
        List<Map<String, Object>> list = new ArrayList<>();
        for (MonAlarmRule rule : rules) {
            if (StringUtils.hasText(metricType) && !metricType.equalsIgnoreCase(rule.getMetricType())) {
                continue;
            }
            if (enabled != null && !enabled.equals(rule.getEnabled())) {
                continue;
            }
            list.add(toRuleView(rule));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ruleDetail(Long id) {
        return toRuleView(findRule(id));
    }

    @Transactional(readOnly = true)
    public MonAlarmRule findRule(Long id) {
        return ruleRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.ALARM_RULE_NOT_FOUND));
    }

    @Transactional
    public Map<String, Object> createRule(MonAlarmRule request) {
        validateRule(request);
        request.setId(null);
        request.setDeleted(false);
        if (request.getEnabled() == null) {
            request.setEnabled(true);
        }
        if (request.getDurationSeconds() == null) {
            request.setDurationSeconds(300);
        }
        if (!StringUtils.hasText(request.getOperator())) {
            request.setOperator("GT");
        }
        if (!StringUtils.hasText(request.getAlarmLevel())) {
            request.setAlarmLevel("P2");
        }
        if (!StringUtils.hasText(request.getChannels())) {
            request.setChannels(OpsConstants.MSG_CHANNEL_INNER);
        }
        return toRuleView(ruleRepository.save(request));
    }

    @Transactional
    public Map<String, Object> updateRule(Long id, MonAlarmRule request) {
        MonAlarmRule rule = findRule(id);
        if (StringUtils.hasText(request.getRuleName())) {
            rule.setRuleName(request.getRuleName());
        }
        if (StringUtils.hasText(request.getMetricType())) {
            rule.setMetricType(request.getMetricType());
        }
        if (StringUtils.hasText(request.getOperator())) {
            rule.setOperator(request.getOperator());
        }
        if (request.getThreshold() != null) {
            rule.setThreshold(request.getThreshold());
        }
        if (request.getDurationSeconds() != null) {
            rule.setDurationSeconds(request.getDurationSeconds());
        }
        if (StringUtils.hasText(request.getAlarmLevel())) {
            rule.setAlarmLevel(request.getAlarmLevel());
        }
        if (request.getChannels() != null) {
            rule.setChannels(request.getChannels());
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        if (request.getTargetCode() != null) {
            rule.setTargetCode(request.getTargetCode());
        }
        if (request.getDescription() != null) {
            rule.setDescription(request.getDescription());
        }
        return toRuleView(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id) {
        MonAlarmRule rule = findRule(id);
        rule.setDeleted(true);
        ruleRepository.save(rule);
    }

    // ================= 告警记录 =================

    @Transactional(readOnly = true)
    public Map<String, Object> pageAlarms(String level, String status, String metricType, Long ruleId,
                                          String keyword, LocalDateTime from, LocalDateTime to,
                                          Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        Specification<MonAlarmRecord> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(level)) {
                predicates.add(cb.equal(root.get("alarmLevel"), level));
            }
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(metricType)) {
                predicates.add(cb.equal(root.get("metricType"), metricType));
            }
            if (ruleId != null) {
                predicates.add(cb.equal(root.get("ruleId"), ruleId));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                // content 为 TEXT 列，H2/MySQL 对 CLOB 做 LOWER/LIKE 不兼容，故只检索规则名与对象编码
                predicates.add(cb.or(cb.like(cb.lower(root.get("ruleName")), kw),
                        cb.like(cb.lower(root.get("objectCode")), kw)));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("firstTime"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("firstTime"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<MonAlarmRecord> page = recordRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "firstTime")));
        return PageSupport.of(page, this::toAlarmView);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> alarmDetail(Long id) {
        MonAlarmRecord record = recordRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "告警记录不存在: " + id));
        Map<String, Object> view = toAlarmView(record);
        view.put("content", record.getContent());
        view.put("ackBy", record.getAckBy());
        view.put("ackTime", record.getAckTime());
        view.put("ackRemark", record.getAckRemark());
        return view;
    }

    /** 确认告警 */
    @Transactional
    public Map<String, Object> ack(Long id, String remark) {
        MonAlarmRecord record = recordRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "告警记录不存在: " + id));
        if (OpsConstants.ALARM_ACKED.equals(record.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该告警已确认，无需重复操作");
        }
        record.setStatus(OpsConstants.ALARM_ACKED);
        record.setAckBy(UserContext.username());
        record.setAckTime(LocalDateTime.now());
        record.setAckRemark(remark);
        return toAlarmView(recordRepository.save(record));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> alarmStats() {
        LocalDateTime from = LocalDate.now().minusDays(6).atStartOfDay();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", recordRepository.count());
        stats.put("open", recordRepository.countByStatusAndDeletedFalse(OpsConstants.ALARM_OPEN));
        stats.put("acked", recordRepository.countByStatusAndDeletedFalse(OpsConstants.ALARM_ACKED));
        stats.put("closed", recordRepository.countByStatusAndDeletedFalse(OpsConstants.ALARM_CLOSED));
        stats.put("p0Open", recordRepository.countByAlarmLevelAndStatusAndDeletedFalse("P0", OpsConstants.ALARM_OPEN));
        List<Map<String, Object>> byLevel = new ArrayList<>();
        for (Object[] row : recordRepository.countByLevelSince(from)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("level", row[0]);
            item.put("count", ((Number) row[1]).longValue());
            byLevel.add(item);
        }
        stats.put("byLevel", byLevel);
        List<Map<String, Object>> topRules = new ArrayList<>();
        for (Object[] row : recordRepository.topRules(from)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleName", row[0]);
            item.put("count", ((Number) row[1]).longValue());
            topRules.add(item);
        }
        stats.put("topRules", topRules);
        stats.put("recent", PageSupport.mapList(
                recordRepository.findTop10ByDeletedFalseOrderByFirstTimeDesc(), this::toAlarmView));
        return stats;
    }

    // ================= 规则评估（定时任务调用） =================

    /**
     * 每分钟评估全部启用的告警规则，命中则生成/合并 {@code mon_alarm_record} 并发布 {@link NotifyEvent}。
     * <p>单条规则评估失败只记日志，不影响其它规则。</p>
     */
    @Transactional
    public void evaluate() {
        List<MonAlarmRule> rules = ruleRepository.findAllByEnabledTrueAndDeletedFalse();
        LocalDateTime now = LocalDateTime.now();
        for (MonAlarmRule rule : rules) {
            try {
                evaluateRule(rule, now);
                rule.setLastEvalTime(now);
                ruleRepository.save(rule);
            } catch (Exception e) {
                log.warn("告警规则评估失败 rule={}: {}", rule.getRuleName(), e.getMessage());
            }
        }
    }

    private void evaluateRule(MonAlarmRule rule, LocalDateTime now) {
        if (OpsConstants.RULE_TASK_FAIL_RATE.equalsIgnoreCase(rule.getMetricType())) {
            evaluateTaskFailRate(rule, now);
        } else if (OpsConstants.RULE_NODE_OFFLINE.equalsIgnoreCase(rule.getMetricType())) {
            evaluateNodeOffline(rule, now);
        } else if (OpsConstants.RULE_API_ERROR_RATE.equalsIgnoreCase(rule.getMetricType())) {
            evaluateApiErrorRate(rule, now);
        }
    }

    /** 任务失败率：按任务维度统计最近一个评估窗口内的失败占比 */
    private void evaluateTaskFailRate(MonAlarmRule rule, LocalDateTime now) {
        LocalDateTime from = now.minusHours(2);
        List<Object[]> rows = pointRepository.groupExchangeByDimension(OpsConstants.DIM_TASK, from, now);
        for (Object[] row : rows) {
            String taskKey = String.valueOf(row[0]);
            if (StringUtils.hasText(rule.getTargetCode()) && !rule.getTargetCode().equals(taskKey)) {
                continue;
            }
            long samples = ((Number) row[2]).longValue();
            long success = ((Number) row[3]).longValue();
            long fail = ((Number) row[4]).longValue();
            if (samples <= 0) {
                continue;
            }
            double rate = Math.round(fail * 10000.0 / samples) / 100.0;
            if (compare(rate, rule.getOperator(), thresholdOf(rule))) {
                String content = String.format("任务 %s 在最近 2 小时内执行 %d 次，失败 %d 次，失败率 %.2f%%（阈值 %.2f%%，规则：%s）",
                        taskName(taskKey), samples, fail, rate, thresholdOf(rule), rule.getRuleName());
                raise(rule, OpsConstants.DIM_TASK, taskKey, rate, content, now);
            }
        }
    }

    /** 节点离线：心跳超时或状态为 OFFLINE 的节点逐个告警 */
    private void evaluateNodeOffline(MonAlarmRule rule, LocalDateTime now) {
        List<MonNode> nodes = nodeRepository.findAllByDeletedFalseOrderByNodeCodeAsc();
        int offlineCount = 0;
        for (MonNode node : nodes) {
            boolean offline = OpsConstants.NODE_OFFLINE.equalsIgnoreCase(node.getStatus());
            if (StringUtils.hasText(rule.getTargetCode())
                    && !rule.getTargetCode().equalsIgnoreCase(node.getNodeCode())) {
                continue;
            }
            if (!offline) {
                continue;
            }
            offlineCount++;
            String content = String.format("前置节点 %s（%s）当前状态离线，最后心跳 %s，请检查网络与节点进程（规则：%s，级别 %s）",
                    node.getNodeName() == null ? node.getNodeCode() : node.getNodeName(),
                    node.getNodeCode(), node.getLastHeartbeat(), rule.getRuleName(), rule.getAlarmLevel());
            raise(rule, OpsConstants.DIM_NODE, node.getNodeCode(), 1.0, content, now);
        }
        if (offlineCount > 0) {
            log.info("节点离线告警评估：命中 {} 个离线节点（规则 {}）", offlineCount, rule.getRuleName());
        }
    }

    /** API 错误率：最近 2 小时调用失败占比 */
    private void evaluateApiErrorRate(MonAlarmRule rule, LocalDateTime now) {
        LocalDateTime from = now.minusHours(2);
        List<Object[]> stat = pointRepository.sumApiStat(from, now);
        if (stat.isEmpty() || stat.get(0) == null) {
            return;
        }
        Object[] row = stat.get(0);
        long samples = ((Number) row[0]).longValue();
        long fail = ((Number) row[2]).longValue();
        if (samples <= 0) {
            return;
        }
        double rate = Math.round(fail * 10000.0 / samples) / 100.0;
        if (compare(rate, rule.getOperator(), thresholdOf(rule))) {
            String content = String.format("开放 API 最近 2 小时调用 %d 次，失败 %d 次，错误率 %.2f%%（阈值 %.2f%%，规则：%s）",
                    samples, fail, rate, thresholdOf(rule), rule.getRuleName());
            raise(rule, "API", "ALL", rate, content, now);
        }
    }

    /** 生成或合并告警记录，并在首次触发时发布通知事件 */
    private void raise(MonAlarmRule rule, String objectType, String objectCode,
                       double metricValue, String content, LocalDateTime now) {
        List<MonAlarmRecord> existing = recordRepository
                .findByRuleIdAndObjectCodeAndStatusAndDeletedFalseOrderByFirstTimeDesc(
                        rule.getId(), objectCode, OpsConstants.ALARM_OPEN);
        MonAlarmRecord record;
        boolean created = false;
        if (!existing.isEmpty()) {
            record = existing.get(0);
            record.setLastTime(now);
            record.setMetricValue(metricValue);
            record.setContent(content);
            record.setTriggerCount((int) (nz(record.getTriggerCount()) + 1));
        } else {
            record = new MonAlarmRecord();
            record.setRuleId(rule.getId());
            record.setRuleName(rule.getRuleName());
            record.setMetricType(rule.getMetricType());
            record.setAlarmLevel(rule.getAlarmLevel());
            record.setMetricValue(metricValue);
            record.setThresholdValue(thresholdOf(rule));
            record.setContent(content);
            record.setStatus(OpsConstants.ALARM_OPEN);
            record.setObjectType(objectType);
            record.setObjectCode(objectCode);
            record.setFirstTime(now);
            record.setLastTime(now);
            record.setTriggerCount(1);
            record.setNotifyStatus(OpsConstants.SEND_PENDING);
            created = true;
        }
        MonAlarmRecord saved = recordRepository.save(record);
        if (created) {
            publishAlarmNotification(rule, saved, content);
        }
    }

    private void publishAlarmNotification(MonAlarmRule rule, MonAlarmRecord record, String content) {
        try {
            String channels = StringUtils.hasText(rule.getChannels())
                    ? rule.getChannels() : OpsConstants.MSG_CHANNEL_INNER;
            NotifyEvent event = NotifyEvent.builder()
                    .receiverId(null)
                    .title("[" + record.getAlarmLevel() + "] " + rule.getRuleName())
                    .content(content)
                    .bizType("ALARM")
                    .bizId(String.valueOf(record.getId()))
                    .channel(channels)
                    .level(errorLevel(record.getAlarmLevel()))
                    .link("/monitor/alarms")
                    .build();
            publisher.publishEvent(event);
            record.setNotifyStatus(OpsConstants.SEND_SENT);
            recordRepository.save(record);
        } catch (Exception e) {
            log.warn("告警通知事件发布失败 alarm={}: {}", record.getId(), e.getMessage());
            record.setNotifyStatus(OpsConstants.SEND_FAILED);
            recordRepository.save(record);
        }
    }

    // ================= 视图与工具 =================

    public Map<String, Object> toRuleView(MonAlarmRule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rule.getId());
        m.put("ruleName", rule.getRuleName());
        m.put("metricType", rule.getMetricType());
        m.put("operator", rule.getOperator());
        m.put("threshold", rule.getThreshold());
        m.put("durationSeconds", rule.getDurationSeconds());
        m.put("level", rule.getAlarmLevel());
        m.put("alarmLevel", rule.getAlarmLevel());
        m.put("channels", rule.getChannels());
        m.put("enabled", rule.getEnabled());
        m.put("targetCode", rule.getTargetCode());
        m.put("description", rule.getDescription());
        m.put("lastEvalTime", rule.getLastEvalTime());
        m.put("createTime", rule.getCreateTime());
        return m;
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
        m.put("createTime", record.getCreateTime());
        return m;
    }

    private void validateRule(MonAlarmRule rule) {
        if (!StringUtils.hasText(rule.getRuleName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "告警规则名称不能为空");
        }
        String metricType = rule.getMetricType();
        if (!StringUtils.hasText(metricType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "指标类型不能为空");
        }
        if (!OpsConstants.RULE_TASK_FAIL_RATE.equalsIgnoreCase(metricType)
                && !OpsConstants.RULE_NODE_OFFLINE.equalsIgnoreCase(metricType)
                && !OpsConstants.RULE_API_ERROR_RATE.equalsIgnoreCase(metricType)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "指标类型仅支持 TASK_FAIL_RATE / NODE_OFFLINE / API_ERROR_RATE");
        }
        if (rule.getThreshold() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "阈值不能为空");
        }
        if (StringUtils.hasText(rule.getAlarmLevel())
                && !List.of("P0", "P1", "P2", "P3").contains(rule.getAlarmLevel().toUpperCase())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "告警级别仅支持 P0~P3");
        }
    }

    private boolean compare(double value, String operator, double threshold) {
        String op = StringUtils.hasText(operator) ? operator.toUpperCase() : "GT";
        return switch (op) {
            case "GE", ">=" -> value >= threshold;
            case "LT", "<" -> value < threshold;
            case "LE", "<=" -> value <= threshold;
            case "EQ", "==" -> Math.abs(value - threshold) < 1e-6;
            case "NE", "!=" -> Math.abs(value - threshold) >= 1e-6;
            default -> value > threshold;
        };
    }

    private double thresholdOf(MonAlarmRule rule) {
        return rule.getThreshold() == null ? 0.0 : rule.getThreshold();
    }

    private String errorLevel(String alarmLevel) {
        if ("P0".equalsIgnoreCase(alarmLevel) || "P1".equalsIgnoreCase(alarmLevel)) {
            return "ERROR";
        }
        if ("P2".equalsIgnoreCase(alarmLevel)) {
            return "WARN";
        }
        return "INFO";
    }

    private String taskName(String taskKey) {
        try {
            Long id = Long.valueOf(taskKey);
            Map<String, Object> task = crossDomain.taskById(id);
            String name = task == null ? null : CrossDomainReader.str(task, "task_name");
            return StringUtils.hasText(name) ? name : ("任务#" + taskKey);
        } catch (NumberFormatException e) {
            return taskKey;
        }
    }

    private long nz(Integer v) {
        return v == null ? 0L : v;
    }
}
