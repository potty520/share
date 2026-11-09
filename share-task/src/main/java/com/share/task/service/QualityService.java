package com.share.task.service;

import com.share.common.api.ErrorCode;
import com.share.common.event.NotifyEvent;
import com.share.common.exception.BizException;
import com.share.common.util.JsonUtil;
import com.share.task.dto.QualityRuleRequest;
import com.share.task.engine.SourceReader;
import com.share.task.engine.TargetWriter;
import com.share.task.entity.TkQualityIssue;
import com.share.task.entity.TkQualityReport;
import com.share.task.entity.TkQualityRule;
import com.share.task.repository.TkQualityIssueRepository;
import com.share.task.repository.TkQualityReportRepository;
import com.share.task.repository.TkQualityRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量管理服务（FR-06）：规则 CRUD、真实 SQL 检核、报告生成、问题工单闭环。
 * <p>检核直接对目标表执行统计 SQL（空值率/重复/格式不匹配/一致性/及时性），
 * 结果落 tk_quality_report 与 tk_quality_issue，未达标时发布通知事件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityService {

    private final TkQualityRuleRepository ruleRepository;

    private final TkQualityReportRepository reportRepository;

    private final TkQualityIssueRepository issueRepository;

    private final JdbcTemplate jdbcTemplate;

    private final TargetWriter targetWriter;

    private final ApplicationEventPublisher publisher;

    /** 规则分页列表。 */
    public Page<Map<String, Object>> pageRules(int pageNum, int pageSize, String ruleType, String keyword, Boolean enabled) {
        List<TkQualityRule> all = ruleRepository.findByDeletedFalseOrderByIdAsc();
        List<TkQualityRule> filtered = new ArrayList<>();
        for (TkQualityRule rule : all) {
            if (ruleType != null && !ruleType.isBlank() && !ruleType.equalsIgnoreCase(rule.getRuleType())) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()) {
                String text = keyword.trim().toLowerCase();
                if (!contains(rule.getRuleName(), text) && !contains(rule.getRuleCode(), text)
                        && !contains(rule.getTargetTable(), text)) {
                    continue;
                }
            }
            if (enabled != null && !enabled.equals(rule.getEnabled())) {
                continue;
            }
            filtered.add(rule);
        }
        int normalizedPageNum = Math.max(1, pageNum);
        int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
        int from = Math.min((normalizedPageNum - 1) * normalizedPageSize, filtered.size());
        int to = Math.min(from + normalizedPageSize, filtered.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (TkQualityRule rule : filtered.subList(from, to)) {
            list.add(BeanMaps.toMap(rule));
        }
        return new PageImpl<>(list, PageRequest.of(normalizedPageNum - 1, normalizedPageSize), filtered.size());
    }

    /** 新建规则。 */
    @Transactional
    public Map<String, Object> createRule(QualityRuleRequest request) {
        validateRuleRequest(request);
        if (ruleRepository.existsByRuleCodeAndDeletedFalse(request.getRuleCode().trim())) {
            throw new BizException(ErrorCode.CONFLICT, "质量规则编码已存在：" + request.getRuleCode());
        }
        TkQualityRule rule = new TkQualityRule();
        applyRule(rule, request);
        rule = ruleRepository.save(rule);
        log.info("创建质量规则 id={} code={}", rule.getId(), rule.getRuleCode());
        return BeanMaps.toMap(rule);
    }

    /** 修改规则。 */
    @Transactional
    public Map<String, Object> updateRule(Long id, QualityRuleRequest request) {
        TkQualityRule rule = requireRule(id);
        if (request.getRuleCode() != null && !request.getRuleCode().isBlank()
                && !request.getRuleCode().trim().equals(rule.getRuleCode())
                && ruleRepository.existsByRuleCodeAndDeletedFalse(request.getRuleCode().trim())) {
            throw new BizException(ErrorCode.CONFLICT, "质量规则编码已存在：" + request.getRuleCode());
        }
        applyRule(rule, request);
        return BeanMaps.toMap(ruleRepository.save(rule));
    }

    /** 逻辑删除规则。 */
    @Transactional
    public void deleteRule(Long id) {
        TkQualityRule rule = requireRule(id);
        rule.setDeleted(true);
        ruleRepository.save(rule);
    }

    /**
     * 执行检核：统计问题行数并产出报告与工单。
     */
    @Transactional
    public Map<String, Object> runRule(Long id, String checker) {
        TkQualityRule rule = requireRule(id);
        String ruleType = rule.getRuleType() == null ? "" : rule.getRuleType().toUpperCase();
        String table = rule.getTargetTable();
        if (table == null || table.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则未配置检核目标表");
        }
        SourceReader.validateIdentifier(table, "检核目标表");
        if (!targetWriter.tableExists(table)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "检核目标表不存在：" + table);
        }
        long start = System.currentTimeMillis();
        long totalRows = count("select count(*) from " + table);
        long problemRows;
        String problemWhere;
        List<Map<String, Object>> samples = new ArrayList<>();
        switch (ruleType) {
            case "COMPLETENESS" -> {
                String field = requireField(rule);
                problemWhere = "(" + field + " is null or trim(cast(" + field + " as varchar(4000))) = '')";
                problemRows = count("select count(*) from " + table + " where " + problemWhere);
                samples = querySamples("select " + field + " as problem_value, count(*) as cnt from " + table
                        + " where " + problemWhere + " group by " + field + " fetch first 10 rows only");
            }
            case "UNIQUENESS" -> {
                String field = requireField(rule);
                problemRows = count("select coalesce(sum(cnt), 0) from (select count(*) as cnt from " + table
                        + " group by " + field + " having count(*) > 1) t");
                samples = querySamples("select " + field + " as problem_value, count(*) as cnt from " + table
                        + " group by " + field + " having count(*) > 1 order by count(*) desc fetch first 10 rows only");
                problemWhere = "重复值 " + field;
            }
            case "FORMAT" -> {
                String field = requireField(rule);
                String regex = rule.getExpression();
                if (regex == null || regex.isBlank()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "规范性规则必须配置正则表达式 expression");
                }
                String escaped = regex.replace("'", "''");
                problemWhere = "(" + field + " is not null and not regexp_like(cast(" + field
                        + " as varchar(4000)), '" + escaped + "'))";
                problemRows = count("select count(*) from " + table + " where " + problemWhere);
                samples = querySamples("select " + field + " as problem_value, count(*) as cnt from " + table
                        + " where " + problemWhere + " group by " + field + " fetch first 10 rows only");
            }
            case "CONSISTENCY" -> {
                String expression = rule.getExpression();
                if (expression == null || expression.isBlank()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "一致性规则必须配置布尔表达式 expression");
                }
                validateExpression(expression);
                problemWhere = "not (" + expression + ")";
                problemRows = count("select count(*) from " + table + " where " + problemWhere);
                samples = querySamples("select * from " + table + " where " + problemWhere + " fetch first 10 rows only");
            }
            case "TIMELINESS" -> {
                String field = requireField(rule);
                double allowedHours = rule.getThreshold() == null ? 24D : rule.getThreshold();
                problemWhere = "(" + field + " is null or datediff('HOUR', cast(" + field
                        + " as timestamp), current_timestamp) > " + (long) allowedHours + ")";
                problemRows = count("select count(*) from " + table + " where " + problemWhere);
                samples = querySamples("select " + field + " as problem_value, count(*) as cnt from " + table
                        + " where " + problemWhere + " group by " + field + " fetch first 10 rows only");
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的质量规则类型：" + rule.getRuleType());
        }

        double ratio = totalRows <= 0 ? 0D : (double) problemRows / totalRows;
        double score = round(Math.max(0D, 100D * (1D - ratio)));
        boolean pass = judgePass(ruleType, ratio, problemRows);
        long costMs = System.currentTimeMillis() - start;

        TkQualityReport report = new TkQualityReport();
        report.setRuleId(rule.getId());
        report.setRuleName(rule.getRuleName());
        report.setRuleType(ruleType);
        report.setTaskId(rule.getTaskId());
        report.setTargetTable(table);
        report.setTargetField(rule.getTargetField());
        report.setCheckTime(LocalDateTime.now());
        report.setTotalRows(totalRows);
        report.setProblemRows(problemRows);
        report.setScore(score);
        report.setPassFlag(pass);
        report.setCostMs(costMs);
        report.setChecker(checker);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleType", ruleType);
        detail.put("targetTable", table);
        detail.put("targetField", rule.getTargetField());
        detail.put("problemWhere", problemWhere);
        detail.put("problemRows", problemRows);
        detail.put("totalRows", totalRows);
        detail.put("problemRatio", round(ratio));
        detail.put("score", score);
        detail.put("pass", pass);
        detail.put("samples", samples);
        report.setIssueDetailJson(JsonUtil.toJson(detail));
        report = reportRepository.save(report);

        List<Map<String, Object>> issues = new ArrayList<>();
        if (!pass) {
            TkQualityIssue issue = new TkQualityIssue();
            issue.setReportId(report.getId());
            issue.setRuleId(rule.getId());
            issue.setRuleName(rule.getRuleName());
            issue.setRuleType(ruleType);
            issue.setTargetTable(table);
            issue.setTargetField(rule.getTargetField());
            issue.setIssueLevel(score >= 90 ? "LOW" : (score >= 70 ? "MEDIUM" : "HIGH"));
            issue.setIssueDesc(truncate("质量规则【" + rule.getRuleName() + "】检核未达标："
                    + problemRows + "/" + totalRows + " 行存在问题（占比 " + round(ratio * 100) + "%），得分 " + score, 500));
            issue.setSampleData(truncate(JsonUtil.toJson(samples), 2000));
            issue.setStatus("OPEN");
            issue.setFoundTime(LocalDateTime.now());
            issue = issueRepository.save(issue);
            issues.add(BeanMaps.toMap(issue));
            publishNotifyInternal(rule, report);
        }

        rule.setLastCheckTime(report.getCheckTime());
        rule.setLastScore(score);
        ruleRepository.save(rule);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", BeanMaps.toMap(report));
        result.put("issues", issues);
        result.put("samples", samples);
        return result;
    }

    /** 报告分页列表。 */
    public Page<Map<String, Object>> pageReports(int pageNum, int pageSize, Long ruleId, String targetTable) {
        List<TkQualityReport> all = ruleId == null
                ? reportRepository.findByDeletedFalseOrderByIdDesc()
                : reportRepository.findByRuleIdAndDeletedFalseOrderByIdDesc(ruleId);
        List<TkQualityReport> filtered = new ArrayList<>();
        for (TkQualityReport report : all) {
            if (targetTable != null && !targetTable.isBlank()
                    && !targetTable.equalsIgnoreCase(report.getTargetTable())) {
                continue;
            }
            filtered.add(report);
        }
        int normalizedPageNum = Math.max(1, pageNum);
        int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
        int from = Math.min((normalizedPageNum - 1) * normalizedPageSize, filtered.size());
        int to = Math.min(from + normalizedPageSize, filtered.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (TkQualityReport report : filtered.subList(from, to)) {
            list.add(BeanMaps.toMap(report));
        }
        return new PageImpl<>(list, PageRequest.of(normalizedPageNum - 1, normalizedPageSize), filtered.size());
    }

    /** 报告详情（含问题工单与问题明细）。 */
    public Map<String, Object> reportDetail(Long id) {
        TkQualityReport report = reportRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "质量报告不存在"));
        Map<String, Object> detail = BeanMaps.toMap(report);
        List<Map<String, Object>> issues = new ArrayList<>();
        for (TkQualityIssue issue : issueRepository.findByReportIdAndDeletedFalse(id)) {
            issues.add(BeanMaps.toMap(issue));
        }
        detail.put("issues", issues);
        return detail;
    }

    /** 问题工单分页列表。 */
    public Page<Map<String, Object>> pageIssues(int pageNum, int pageSize, String status, Long ruleId, String level) {
        List<TkQualityIssue> all = issueRepository.findByDeletedFalseOrderByIdDesc();
        List<TkQualityIssue> filtered = new ArrayList<>();
        for (TkQualityIssue issue : all) {
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(issue.getStatus())) {
                continue;
            }
            if (ruleId != null && !ruleId.equals(issue.getRuleId())) {
                continue;
            }
            if (level != null && !level.isBlank() && !level.equalsIgnoreCase(issue.getIssueLevel())) {
                continue;
            }
            filtered.add(issue);
        }
        int normalizedPageNum = Math.max(1, pageNum);
        int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
        int from = Math.min((normalizedPageNum - 1) * normalizedPageSize, filtered.size());
        int to = Math.min(from + normalizedPageSize, filtered.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (TkQualityIssue issue : filtered.subList(from, to)) {
            list.add(BeanMaps.toMap(issue));
        }
        return new PageImpl<>(list, PageRequest.of(normalizedPageNum - 1, normalizedPageSize), filtered.size());
    }

    /** 问题工单整改闭环。 */
    @Transactional
    public Map<String, Object> closeIssue(Long id, String remark, String operator) {
        TkQualityIssue issue = issueRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "质量问题工单不存在"));
        if ("CLOSED".equalsIgnoreCase(issue.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该工单已关闭");
        }
        issue.setStatus("CLOSED");
        issue.setCloseTime(LocalDateTime.now());
        issue.setCloseBy(operator == null || operator.isBlank() ? "system" : operator);
        issue.setCloseRemark(remark);
        return BeanMaps.toMap(issueRepository.save(issue));
    }

    // ====================== 内部辅助 ======================

    /** 判定是否达标：完整性/唯一性/规范性/一致性按阈值占比，及时性要求问题率不超过 5%。 */
    private boolean judgePass(String ruleType, double ratio, long problemRows) {
        return switch (ruleType) {
            case "COMPLETENESS" -> ratio <= 0.05D;
            case "UNIQUENESS" -> ratio <= 0.01D;
            case "FORMAT" -> ratio <= 0.05D;
            case "CONSISTENCY" -> ratio <= 0.02D;
            default -> ratio <= 0.05D;
        };
    }

    private long count(String sql) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "质量检核 SQL 执行失败：" + e.getMessage());
        }
    }

    private List<Map<String, Object>> querySamples(String sql) {
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("质量问题样例查询失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String requireField(TkQualityRule rule) {
        String field = rule.getTargetField();
        if (field == null || field.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则未配置检核字段 targetField");
        }
        SourceReader.validateIdentifier(field, "检核字段");
        return field;
    }

    /** 一致性表达式安全校验：禁止分号与注释，避免拼接注入。 */
    private void validateExpression(String expression) {
        String lower = expression.toLowerCase();
        if (lower.contains(";") || lower.contains("--") || lower.contains("/*")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "一致性表达式包含非法字符");
        }
    }

    private void validateRuleRequest(QualityRuleRequest request) {
        if (request.getRuleCode() == null || request.getRuleCode().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ruleCode 不能为空");
        }
        if (request.getRuleName() == null || request.getRuleName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ruleName 不能为空");
        }
        if (request.getRuleType() == null || request.getRuleType().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ruleType 不能为空");
        }
        String type = request.getRuleType().toUpperCase();
        if (!List.of("COMPLETENESS", "UNIQUENESS", "FORMAT", "CONSISTENCY", "TIMELINESS").contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的规则类型：" + request.getRuleType());
        }
        if (request.getTargetTable() == null || request.getTargetTable().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "targetTable 不能为空");
        }
        SourceReader.validateIdentifier(request.getTargetTable(), "检核目标表");
    }

    private void applyRule(TkQualityRule rule, QualityRuleRequest request) {
        if (request.getRuleCode() != null && !request.getRuleCode().isBlank()) {
            rule.setRuleCode(request.getRuleCode().trim());
        }
        if (request.getRuleName() != null && !request.getRuleName().isBlank()) {
            rule.setRuleName(request.getRuleName().trim());
        }
        if (request.getRuleType() != null) {
            rule.setRuleType(request.getRuleType().toUpperCase());
        }
        if (request.getTaskId() != null) {
            rule.setTaskId(request.getTaskId());
        }
        if (request.getTargetTable() != null) {
            rule.setTargetTable(request.getTargetTable().trim());
        }
        if (request.getTargetField() != null) {
            rule.setTargetField(request.getTargetField().trim());
        }
        if (request.getExpression() != null) {
            rule.setExpression(request.getExpression());
        }
        if (request.getThreshold() != null) {
            rule.setThreshold(request.getThreshold());
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        if (request.getCheckCron() != null) {
            rule.setCheckCron(request.getCheckCron().isBlank() ? null : request.getCheckCron().trim());
        }
        if (request.getDescription() != null) {
            rule.setDescription(request.getDescription());
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(Boolean.TRUE);
        }
    }

    private TkQualityRule requireRule(Long id) {
        return ruleRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "质量规则不存在"));
    }

    private void publishNotifyInternal(TkQualityRule rule, TkQualityReport report) {
        try {
            publisher.publishEvent(NotifyEvent.builder()
                    .title("数据质量检核未达标")
                    .content("规则【" + rule.getRuleName() + "】检核得分 " + report.getScore()
                            + "，问题行数 " + report.getProblemRows() + " / " + report.getTotalRows())
                    .bizType("TASK")
                    .bizId(String.valueOf(report.getId()))
                    .channel("INNER")
                    .level("WARN")
                    .link("/task/quality")
                    .build());
        } catch (Exception e) {
            log.warn("发布质量告警通知失败：{}", e.getMessage());
        }
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
