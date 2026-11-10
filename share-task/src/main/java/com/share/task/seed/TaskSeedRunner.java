package com.share.task.seed;

import com.share.common.util.JsonUtil;
import com.share.task.dto.MappingRule;
import com.share.task.dto.TransformResult;
import com.share.task.engine.RowChecksum;
import com.share.task.engine.TargetWriter;
import com.share.task.engine.TransformEngine;
import com.share.task.entity.SwBatch;
import com.share.task.entity.SwCheckpoint;
import com.share.task.entity.SwTransferLog;
import com.share.task.entity.TkQualityIssue;
import com.share.task.entity.TkQualityReport;
import com.share.task.entity.TkQualityRule;
import com.share.task.entity.TkTask;
import com.share.task.entity.TkTaskDep;
import com.share.task.entity.TkTaskInstance;
import com.share.task.entity.TkTemplate;
import com.share.task.repository.SwBatchRepository;
import com.share.task.repository.SwCheckpointRepository;
import com.share.task.repository.SwTransferLogRepository;
import com.share.task.repository.TkQualityIssueRepository;
import com.share.task.repository.TkQualityReportRepository;
import com.share.task.repository.TkQualityRuleRepository;
import com.share.task.repository.TkTaskDepRepository;
import com.share.task.repository.TkTaskInstanceRepository;
import com.share.task.repository.TkTaskRepository;
import com.share.task.repository.TkTaskVersionRepository;
import com.share.task.repository.TkTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * share-task 种子数据（@Order(30)）：
 * <ul>
 *   <li>内置样例源表 tk_demo_enterprise / tk_demo_person / tk_demo_social / tk_demo_credit，真实生成 50~200 行；</li>
 *   <li>3 个演示任务（企业基础信息全量同步、社保参保增量同步、信用信息定时归集），其中 1 个配置 cron；</li>
 *   <li>2 个已完成历史实例 + 批次 + 流转日志 + 断点，目标端真实写入，可用一致性核对验证；</li>
 *   <li>2 个任务模板、2 条质量规则、1 份质量报告 + 1 个待整改工单。</li>
 * </ul>
 * 数据源/目录等跨域表只读且容错：对应域未初始化时不影响本模块种子执行。
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class TaskSeedRunner implements CommandLineRunner {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TkTaskRepository taskRepository;

    private final TkTaskVersionRepository versionRepository;

    private final TkTaskInstanceRepository instanceRepository;

    private final TkTaskDepRepository depRepository;

    private final TkTemplateRepository templateRepository;

    private final SwBatchRepository batchRepository;

    private final SwTransferLogRepository transferLogRepository;

    private final SwCheckpointRepository checkpointRepository;

    private final TkQualityRuleRepository ruleRepository;

    private final TkQualityReportRepository reportRepository;

    private final TkQualityIssueRepository issueRepository;

    private final TransformEngine transformEngine;

    private final TargetWriter targetWriter;

    private final JdbcTemplate jdbcTemplate;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        createDemoSourceTables();
        setupApplicationTasks();
        if (templateRepository.countByDeletedFalse() == 0) {
            seedTemplates();
        }
        if (batchRepository.countByDeletedFalse() == 0) {
            seedHistory();
        }
        if (ruleRepository.countByDeletedFalse() == 0) {
            seedQuality();
        }
        log.info("share-task 种子数据检查完成：任务 {} 个，批次 {} 个，质量规则 {} 条",
                taskRepository.countByDeletedFalse(), batchRepository.countByDeletedFalse(),
                ruleRepository.countByDeletedFalse());
    }

    // ====================== 内置样例源表 ======================

    /** 建内置演示源表并生成真实数据（幂等）。 */
    private void createDemoSourceTables() {
        jdbcTemplate.execute("create table if not exists tk_demo_enterprise ("
                + "id bigint, uniscid varchar(32), enterprise_name varchar(200), legal_person varchar(64), "
                + "phone varchar(32), address varchar(300), industry_code varchar(20), register_date varchar(20), "
                + "reg_capital bigint, status varchar(20))");
        jdbcTemplate.execute("create table if not exists tk_demo_person ("
                + "id bigint, id_card varchar(32), person_name varchar(64), phone varchar(32), gender_code varchar(8), "
                + "birth_date varchar(20), address varchar(300), nation_code varchar(8), update_time varchar(32))");
        jdbcTemplate.execute("create table if not exists tk_demo_social ("
                + "id bigint, id_card varchar(32), person_name varchar(64), social_no varchar(40), company_name varchar(200), "
                + "insurance_type varchar(20), base_amount bigint, pay_month varchar(20), status varchar(20))");
        jdbcTemplate.execute("create table if not exists tk_demo_credit ("
                + "id bigint, uniscid varchar(32), enterprise_name varchar(200), credit_level varchar(8), credit_score bigint, "
                + "evaluate_date varchar(20), blacklist_flag varchar(8), remark varchar(300))");

        if (countOf("tk_demo_enterprise") == 0) {
            fillEnterprise();
        }
        if (countOf("tk_demo_person") == 0) {
            fillPerson();
        }
        if (countOf("tk_demo_social") == 0) {
            fillSocial();
        }
        if (countOf("tk_demo_credit") == 0) {
            fillCredit();
        }
    }

    private void fillEnterprise() {
        String[] cities = {"杭州市", "宁波市", "温州市", "绍兴市", "嘉兴市", "湖州市"};
        String[] industries = {"A01", "B06", "C13", "F51", "I63", "J65", "K70", "L72"};
        String[] surnames = {"张", "王", "李", "赵", "陈", "刘", "杨", "周", "吴", "徐"};
        String[] givenNames = {"明", "伟", "芳", "娜", "强", "磊", "洋", "静", "敏", "军"};
        List<Object[]> args = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            LocalDate base = LocalDate.of(2015, 1, 1).plusDays(i * 11L);
            String registerDate = switch (i % 3) {
                case 0 -> base.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                case 1 -> base.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                default -> base.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            };
            String status = i % 17 == 0 ? "CANCEL" : (i % 23 == 0 ? "REVOKE" : "NORMAL");
            args.add(new Object[]{
                    (long) i,
                    "91330100MA2" + String.format("%06d", i),
                    cities[i % cities.length] + industries[i % industries.length] + "示范企业" + i + "号",
                    surnames[i % surnames.length] + givenNames[(i * 3) % givenNames.length],
                    "138" + String.format("%08d", 1000000 + i * 13L),
                    cities[i % cities.length] + "文一西路" + (100 + i) + "号",
                    industries[i % industries.length],
                    registerDate,
                    100L + i * 25L,
                    status
            });
        }
        jdbcTemplate.batchUpdate("insert into tk_demo_enterprise values (?,?,?,?,?,?,?,?,?,?)", args);
        log.info("内置源表 tk_demo_enterprise 初始化 {} 行", args.size());
    }

    private void fillPerson() {
        String[] surnames = {"张", "王", "李", "赵", "陈", "刘", "杨", "周"};
        String[] givenNames = {"建国", "秀英", "志强", "丽华", "海涛", "晓燕", "国庆", "文静"};
        String[] nations = {"01", "01", "01", "05", "08", "11", "01", "02"};
        List<Object[]> args = new ArrayList<>();
        for (int i = 1; i <= 80; i++) {
            LocalDate birth = LocalDate.of(1965, 1, 1).plusDays(i * 137L);
            String idCard = "330102" + birth.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + String.format("%04d", 1000 + i);
            args.add(new Object[]{
                    (long) i,
                    idCard,
                    surnames[i % surnames.length] + givenNames[i % givenNames.length],
                    "139" + String.format("%08d", 2000000 + i * 19L),
                    i % 2 == 0 ? "2" : "1",
                    birth.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    "杭州市西湖区文三路" + (10 + i) + "号",
                    nations[i % nations.length],
                    "2026-11-0" + (i % 9 + 1) + " 08:30:00"
            });
        }
        jdbcTemplate.batchUpdate("insert into tk_demo_person values (?,?,?,?,?,?,?,?,?)", args);
        log.info("内置源表 tk_demo_person 初始化 {} 行", args.size());
    }

    private void fillSocial() {
        String[] surnames = {"张", "王", "李", "赵", "陈", "刘"};
        String[] givenNames = {"建国", "秀英", "志强", "丽华", "海涛", "晓燕"};
        String[] insuranceTypes = {"养老", "医疗", "失业", "工伤", "生育"};
        String[] companies = {"杭州数字科技有限公司", "宁波智造集团有限公司", "温州商贸有限公司",
                "绍兴纺织股份有限公司", "嘉兴新材料有限公司"};
        List<Object[]> args = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            LocalDate birth = LocalDate.of(1975, 1, 1).plusDays(i * 211L);
            String idCard = "330102" + birth.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + String.format("%04d", 3000 + i);
            LocalDate payMonth = LocalDate.of(2026, 9, 1).plusMonths(i % 3);
            String payMonthText = switch (i % 3) {
                case 0 -> payMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                case 1 -> payMonth.format(DateTimeFormatter.ofPattern("yyyyMM"));
                default -> payMonth.format(DateTimeFormatter.ofPattern("yyyy/MM"));
            };
            String status = i % 13 == 0 ? "STOP" : "NORMAL";
            args.add(new Object[]{
                    (long) i,
                    idCard,
                    surnames[i % surnames.length] + givenNames[i % givenNames.length],
                    "SB3301" + String.format("%08d", 5000000 + i * 7L),
                    companies[i % companies.length],
                    insuranceTypes[i % insuranceTypes.length],
                    3000L + i * 120L,
                    payMonthText,
                    status
            });
        }
        jdbcTemplate.batchUpdate("insert into tk_demo_social values (?,?,?,?,?,?,?,?,?)", args);
        log.info("内置源表 tk_demo_social 初始化 {} 行", args.size());
    }

    private void fillCredit() {
        String[] cities = {"杭州市", "宁波市", "温州市", "绍兴市", "嘉兴市"};
        String[] levels = {"A", "B", "C", "D"};
        List<Object[]> args = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            LocalDate evaluate = LocalDate.of(2026, 10, 1).plusDays(i % 28);
            String dateText = switch (i % 3) {
                case 0 -> evaluate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                case 1 -> evaluate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                default -> evaluate.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            };
            args.add(new Object[]{
                    (long) i,
                    "91330100MA2" + String.format("%06d", i),
                    cities[i % cities.length] + "信用示范企业" + i + "号",
                    levels[i % levels.length],
                    60L + (i * 7) % 41,
                    dateText,
                    i % 11 == 0 ? "Y" : "N",
                    "年度信用评价结果"
            });
        }
        jdbcTemplate.batchUpdate("insert into tk_demo_credit values (?,?,?,?,?,?,?,?)", args);
        log.info("内置源表 tk_demo_credit 初始化 {} 行", args.size());
    }

    // ====================== 演示任务 ======================

    /** 创建 3 个演示任务（已存在则跳过），并建立一条 DAG 依赖。 */
    private void setupApplicationTasks() {
        if (taskRepository.countByDeletedFalse() > 0) {
            return;
        }
        TkTask enterpriseTask = createTask("TASK-ENT-FULL", "企业基础信息全量同步", "FULL",
                "H2_TABLE", "tk_demo_enterprise", null,
                "H2_TABLE", "tgt_enterprise_info", null,
                enterpriseMapping(), enterpriseOperators(), null, null,
                2L, "财务部", "全量抽取企业基础信息，手机号静态脱敏后写入平台库表");
        TkTask socialTask = createTask("TASK-SOCIAL-INC", "社保参保增量同步", "INCREMENT",
                "H2_TABLE", "tk_demo_social", null,
                "H2_TABLE", "tgt_social_security", null,
                socialMapping(), socialOperators(), null, null,
                4L, "人力资源部", "按参保状态过滤的增量同步，身份证号脱敏、缴费月份标准化");
        TkTask creditTask = createTask("TASK-CREDIT-CRON", "信用信息定时归集", "FULL",
                "H2_TABLE", "tk_demo_credit", null,
                "CSV_FILE", null, "data/exchange/target/credit_info.csv",
                creditMapping(), creditOperators(), null, "0 0/30 * * * ?",
                3L, "运营管理部", "每 30 分钟归集一次信用评价结果，落盘为 CSV 供前置节点摆渡");
        creditTask.setStatus("READY");
        taskRepository.save(creditTask);

        // DAG 依赖：企业基础信息同步成功后自动触发社保增量同步
        TkTaskDep dep = new TkTaskDep();
        dep.setTaskId(socialTask.getId());
        dep.setUpTaskId(enterpriseTask.getId());
        dep.setUpTaskName(enterpriseTask.getTaskName());
        dep.setDepType("SUCCESS");
        depRepository.save(dep);
        log.info("演示任务初始化完成：{},{},{}", enterpriseTask.getTaskCode(), socialTask.getTaskCode(), creditTask.getTaskCode());
    }

    private TkTask createTask(String code, String name, String type, String sourceType, String sourceTable,
                              String sourceFilePath, String targetType, String targetTable, String targetFilePath,
                              List<MappingRule> mapping, List<Map<String, Object>> operators,
                              String filterCondition, String scheduleCron, Long deptId, String deptName, String remark) {
        TkTask task = new TkTask();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setTaskType(type);
        task.setSourceId(1L);
        task.setSourceName("平台内置演示源（H2 样例表）");
        task.setSourceType(sourceType);
        task.setSourceTable(sourceTable);
        task.setSourceFilePath(sourceFilePath);
        task.setTargetType(targetType);
        task.setTargetTable(targetTable);
        task.setTargetFilePath(targetFilePath);
        task.setMappingJson(JsonUtil.toJson(mapping));
        task.setOperatorsJson(JsonUtil.toJson(operators));
        task.setFilterCondition(filterCondition);
        task.setScheduleCron(scheduleCron);
        task.setEnabled(Boolean.TRUE);
        task.setStatus("READY");
        task.setDeptId(deptId);
        task.setDeptName(deptName);
        task.setRemark(remark);
        task = taskRepository.save(task);
        saveVersion(task, "CREATE", "演示数据初始化");
        return task;
    }

    private List<MappingRule> enterpriseMapping() {
        List<MappingRule> mapping = new ArrayList<>();
        mapping.add(rule("uniscid", "uniscid", null));
        mapping.add(rule("enterprise_name", "enterprise_name", "TRIM"));
        mapping.add(rule("legal_person", "legal_person", null));
        mapping.add(rule("phone", "contact_phone", "MASK_PHONE"));
        mapping.add(rule("address", "address", null));
        mapping.add(rule("industry_code", "industry_name", null));
        mapping.add(rule("register_date", "register_date", "TO_DATE"));
        mapping.add(rule("reg_capital", "reg_capital", null));
        mapping.add(rule("status", "data_status", null));
        MappingRule masked = rule("legal_person", "legal_person_masked", null);
        masked.setMaskType("MASK");
        masked.setMaskParam("1,0");
        mapping.add(masked);
        return mapping;
    }

    private List<Map<String, Object>> enterpriseOperators() {
        List<Map<String, Object>> operators = new ArrayList<>();
        operators.add(operator("DEDUP", params("fields", List.of("uniscid"), "keep", "first")));
        operators.add(operator("FILL_NULL", params("fields", List.of("legal_person", "address"), "value", "未知")));
        operators.add(operator("CODE_TRANSLATE", params("field", "industry_name",
                "mapping", Map.of("A01", "农林牧渔业", "B06", "采矿业", "C13", "制造业",
                        "F51", "批发零售业", "I63", "信息传输业", "J65", "软件服务业",
                        "K70", "房地产业", "L72", "商务服务业"),
                "defaultValue", "其他行业")));
        operators.add(operator("CODE_TRANSLATE", params("field", "data_status",
                "mapping", Map.of("NORMAL", "正常", "CANCEL", "注销", "REVOKE", "吊销"),
                "defaultValue", "未知")));
        return operators;
    }

    private List<MappingRule> socialMapping() {
        List<MappingRule> mapping = new ArrayList<>();
        mapping.add(rule("person_name", "person_name", null));
        mapping.add(rule("id_card", "id_card", "MASK_IDCARD"));
        mapping.add(rule("social_no", "social_no", null));
        mapping.add(rule("company_name", "company_name", null));
        mapping.add(rule("insurance_type", "insurance_type", null));
        mapping.add(rule("base_amount", "base_amount", "TO_INT"));
        mapping.add(rule("pay_month", "pay_month", "TO_DATE"));
        mapping.add(rule("status", "pay_status", null));
        return mapping;
    }

    private List<Map<String, Object>> socialOperators() {
        List<Map<String, Object>> operators = new ArrayList<>();
        // 注意：算子运行在“字段映射之后”，过滤条件必须使用目标字段名 pay_status
        operators.add(operator("FILTER", params("condition", "pay_status = 'NORMAL'")));
        operators.add(operator("DEDUP", params("fields", List.of("id_card", "pay_month"))));
        operators.add(operator("CODE_TRANSLATE", params("field", "pay_status",
                "mapping", Map.of("NORMAL", "正常缴费", "STOP", "暂停缴费"), "defaultValue", "未知")));
        return operators;
    }

    private List<MappingRule> creditMapping() {
        List<MappingRule> mapping = new ArrayList<>();
        mapping.add(rule("uniscid", "uniscid", null));
        mapping.add(rule("enterprise_name", "enterprise_name", null));
        mapping.add(rule("credit_level", "credit_level", "UPPER"));
        mapping.add(rule("credit_score", "credit_score", "TO_INT"));
        mapping.add(rule("evaluate_date", "evaluate_date", "TO_DATE"));
        mapping.add(rule("blacklist_flag", "blacklist_flag", null));
        mapping.add(rule("remark", "remark", null));
        return mapping;
    }

    private List<Map<String, Object>> creditOperators() {
        List<Map<String, Object>> operators = new ArrayList<>();
        operators.add(operator("DEDUP", params("fields", List.of("uniscid"))));
        operators.add(operator("ADD_CONST", params("field", "data_source", "value", "credit-collect")));
        return operators;
    }

    // ====================== 历史实例与批次 ======================

    /** 为前两个任务生成已完成历史实例（目标端真实写入，可被一致性核对）。 */
    private void seedHistory() {
        List<TkTask> tasks = taskRepository.findByDeletedFalseOrderByIdDesc();
        if (tasks.size() < 2) {
            return;
        }
        TkTask enterpriseTask = tasks.stream()
                .filter(task -> "TASK-ENT-FULL".equals(task.getTaskCode())).findFirst().orElse(tasks.get(0));
        TkTask creditTask = tasks.stream()
                .filter(task -> "TASK-CREDIT-CRON".equals(task.getTaskCode())).findFirst()
                .orElse(tasks.get(tasks.size() - 1));
        seedOneHistory(enterpriseTask, "SW2026110802000012300041",
                LocalDateTime.now().minusDays(1).withHour(2).withMinute(0).withSecond(0).withNano(0));
        seedOneHistory(creditTask, "SW2026110802300012300042",
                LocalDateTime.now().minusHours(6).withMinute(30).withSecond(0).withNano(0));
    }

    private void seedOneHistory(TkTask task, String batchId, LocalDateTime start) {
        List<Map<String, Object>> sourceRows;
        if ("CSV_FILE".equalsIgnoreCase(task.getSourceType())) {
            sourceRows = new ArrayList<>();
        } else {
            sourceRows = jdbcTemplate.queryForList("select * from " + task.getSourceTable());
        }
        List<MappingRule> mapping = transformEngine.parseMapping(task.getMappingJson());
        List<Map<String, Object>> operators = transformEngine.parseOperators(task.getOperatorsJson());
        TransformResult transform = transformEngine.transform(sourceRows, mapping, operators, task.getFilterCondition());
        TargetWriter.WriteResult writeResult = targetWriter.write(task, transform.getColumns(),
                transform.getRows(), batchId, null);
        String checksum = RowChecksum.of(transform.getColumns(), transform.getRows());
        long bytes = writeResult.getBytes() > 0 ? writeResult.getBytes()
                : RowChecksum.bytesOf(transform.getColumns(), transform.getRows());

        long costMs = 12000L + sourceRows.size() * 38L;
        LocalDateTime end = start.plusNanos(costMs * 1_000_000L);

        SwBatch batch = new SwBatch();
        batch.setBatchId(batchId);
        batch.setTaskId(task.getId());
        batch.setTaskName(task.getTaskName());
        batch.setTaskType(task.getTaskType());
        batch.setSourceType(task.getSourceType());
        batch.setTargetType(task.getTargetType());
        batch.setSourceId(task.getSourceId());
        batch.setTargetTable("CSV_FILE".equalsIgnoreCase(task.getTargetType())
                ? TargetWriter.resolveTargetPath(task).toString() : task.getTargetTable());
        batch.setColumnsJson(JsonUtil.toJson(transform.getColumns()));
        batch.setReadRows((long) sourceRows.size());
        batch.setTotalRows((long) transform.getRows().size());
        batch.setSuccessRows(writeResult.getWrittenRows());
        batch.setFailedRows((long) Math.max(0, sourceRows.size() - transform.getRows().size()));
        batch.setByteCount(bytes);
        batch.setCostMs(costMs);
        batch.setChecksum(checksum);
        batch.setStatus("SUCCESS");
        batch.setStartTime(start);
        batch.setEndTime(end);
        batch.setDeptId(task.getDeptId());
        batchRepository.save(batch);

        TkTaskInstance instance = new TkTaskInstance();
        instance.setTaskId(task.getId());
        instance.setTaskCode(task.getTaskCode());
        instance.setTaskName(task.getTaskName());
        instance.setBatchId(batchId);
        instance.setTriggerType("CRON");
        instance.setStatus("SUCCESS");
        instance.setProgress(100);
        instance.setReadRows((long) sourceRows.size());
        instance.setTotalRows((long) transform.getRows().size());
        instance.setWriteRows(writeResult.getWrittenRows());
        instance.setFailedRows((long) Math.max(0, sourceRows.size() - transform.getRows().size()));
        instance.setChecksum(checksum);
        instance.setVerifyStatus("NOT_VERIFIED");
        instance.setStartTime(start);
        instance.setEndTime(end);
        instance.setCostMs(costMs);
        instance.setOperatorName("scheduler");
        instance.setDeptId(task.getDeptId());
        instanceRepository.save(instance);

        appendHistoryLogs(task, instance, batchId, sourceRows.size(), transform.getRows().size(),
                writeResult.getWrittenRows(), checksum, start);
        saveHistoryCheckpoint(task, batchId, transform.getRows().size());

        task.setLastRunTime(end);
        task.setLastBatchId(batchId);
        task.setStatus("READY");
        taskRepository.save(task);
        log.info("生成历史批次 {}（任务 {}，写入 {} 行）", batchId, task.getTaskCode(), writeResult.getWrittenRows());
    }

    private void appendHistoryLogs(TkTask task, TkTaskInstance instance, String batchId, int readRows,
                                   int outputRows, long writeRows, String checksum, LocalDateTime start) {
        appendLog(task, instance, batchId, "READ", "INFO",
                "读取源端完成：" + task.getSourceType() + " / " + task.getSourceTable() + "，行数 " + readRows,
                (long) readRows, 320L, start);
        appendLog(task, instance, batchId, "TRANSFORM", "INFO",
                "字段映射与算子链处理完成，输出 " + outputRows + " 行", (long) outputRows, 180L, start.plusSeconds(1));
        appendLog(task, instance, batchId, "MASK", "INFO",
                "静态脱敏完成：手机号/身份证等敏感字段已按规则处理", (long) outputRows, 60L, start.plusSeconds(2));
        appendLog(task, instance, batchId, "WRITE", "INFO",
                "写入目标端完成，行数 " + writeRows, writeRows, 8600L, start.plusSeconds(3));
        appendLog(task, instance, batchId, "VERIFY", "INFO",
                "生成数据校验值：" + checksum, (long) outputRows, 40L, start.plusSeconds(4));
        appendLog(task, instance, batchId, "FINISH", "INFO",
                "批次 " + batchId + " 执行成功", (long) outputRows, 12000L, start.plusSeconds(5));
    }

    private void appendLog(TkTask task, TkTaskInstance instance, String batchId, String stage, String level,
                           String message, Long rows, Long costMs, LocalDateTime logTime) {
        SwTransferLog transferLog = new SwTransferLog();
        transferLog.setTaskId(task.getId());
        transferLog.setInstanceId(instance.getId());
        transferLog.setBatchId(batchId);
        transferLog.setStage(stage);
        transferLog.setLevel(level);
        transferLog.setMessage(message);
        transferLog.setRowsCount(rows);
        transferLog.setCostMs(costMs);
        transferLog.setLogTime(logTime);
        transferLogRepository.save(transferLog);
    }

    private void saveHistoryCheckpoint(TkTask task, String batchId, int totalRows) {
        SwCheckpoint checkpoint = new SwCheckpoint();
        checkpoint.setTaskId(task.getId());
        checkpoint.setBatchId(batchId);
        checkpoint.setChunkNo(Math.max(1, (totalRows + 499) / 500));
        checkpoint.setOffsetRow((long) totalRows);
        checkpoint.setTotalRows((long) totalRows);
        checkpoint.setState("DONE");
        checkpoint.setCheckpointTime(LocalDateTime.now());
        checkpoint.setRemark("历史批次断点：数据已全部写入");
        checkpointRepository.save(checkpoint);
    }

    // ====================== 模板与质量 ======================

    private void seedTemplates() {
        TkTemplate full = new TkTemplate();
        full.setTemplateCode("TPL_DB_FULL");
        full.setTemplateName("库表全量同步模板");
        full.setTaskType("FULL");
        full.setCategory("库表同步");
        full.setDescription("H2/MySQL 库表 → 平台库表：自动建表、按批次幂等写入，适合周期性全量归集");
        full.setDefinitionJson("{\"taskType\":\"FULL\",\"sourceType\":\"H2_TABLE\",\"targetType\":\"H2_TABLE\","
                + "\"operators\":[{\"type\":\"DEDUP\"},{\"type\":\"FILL_NULL\"}]}");
        full.setEnabled(Boolean.TRUE);
        templateRepository.save(full);

        TkTemplate incremental = new TkTemplate();
        incremental.setTemplateCode("TPL_FILE_INCREMENT");
        incremental.setTemplateName("文件增量同步模板");
        incremental.setTaskType("INCREMENT");
        incremental.setCategory("文件同步");
        incremental.setDescription("CSV 文件 → 目标表：适合前置节点文件摆渡后的入库加工与校验");
        incremental.setDefinitionJson("{\"taskType\":\"INCREMENT\",\"sourceType\":\"CSV_FILE\",\"targetType\":\"H2_TABLE\","
                + "\"operators\":[{\"type\":\"FILTER\",\"params\":{\"condition\":\"status = 'NORMAL'\"}}]}");
        incremental.setEnabled(Boolean.TRUE);
        templateRepository.save(incremental);
        log.info("任务模板初始化完成：TPL_DB_FULL、TPL_FILE_INCREMENT");
    }

    private void seedQuality() {
        Long taskId = taskRepository.findByDeletedFalseOrderByIdDesc().stream()
                .filter(task -> "TASK-ENT-FULL".equals(task.getTaskCode()))
                .map(TkTask::getId).findFirst().orElse(null);

        TkQualityRule completeness = new TkQualityRule();
        completeness.setRuleCode("QR-ENT-COMPLETENESS");
        completeness.setRuleName("企业信息-联系电话完整性");
        completeness.setRuleType("COMPLETENESS");
        completeness.setTaskId(taskId);
        completeness.setTargetTable("tgt_enterprise_info");
        completeness.setTargetField("contact_phone");
        completeness.setThreshold(0.05D);
        completeness.setEnabled(Boolean.TRUE);
        completeness.setDescription("检核企业目标表联系电话空值率，允许阈值 5%");
        completeness = ruleRepository.save(completeness);

        TkQualityRule uniqueness = new TkQualityRule();
        uniqueness.setRuleCode("QR-ENT-UNIQUENESS");
        uniqueness.setRuleName("企业信息-统一社会信用代码唯一性");
        uniqueness.setRuleType("UNIQUENESS");
        uniqueness.setTaskId(taskId);
        uniqueness.setTargetTable("tgt_enterprise_info");
        uniqueness.setTargetField("uniscid");
        uniqueness.setThreshold(0D);
        uniqueness.setEnabled(Boolean.TRUE);
        uniqueness.setDescription("检核统一社会信用代码是否存在重复，重复率应不超过 1%");
        uniqueness = ruleRepository.save(uniqueness);

        // 1 份历史质量报告 + 1 个待整改工单（用于前端质量页演示）
        TkQualityReport report = new TkQualityReport();
        report.setRuleId(completeness.getId());
        report.setRuleName(completeness.getRuleName());
        report.setRuleType("COMPLETENESS");
        report.setTaskId(taskId);
        report.setTargetTable("tgt_enterprise_info");
        report.setTargetField("contact_phone");
        report.setCheckTime(LocalDateTime.now().minusHours(5));
        report.setTotalRows(120L);
        report.setProblemRows(8L);
        report.setScore(93.33D);
        report.setPassFlag(Boolean.FALSE);
        report.setCostMs(86L);
        report.setChecker("scheduler");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleType", "COMPLETENESS");
        detail.put("targetTable", "tgt_enterprise_info");
        detail.put("targetField", "contact_phone");
        detail.put("problemRows", 8);
        detail.put("totalRows", 120);
        detail.put("problemRatio", 0.0667);
        detail.put("score", 93.33);
        detail.put("pass", false);
        detail.put("samples", List.of(Map.of("problem_value", "空值", "cnt", 8)));
        report.setIssueDetailJson(JsonUtil.toJson(detail));
        report = reportRepository.save(report);

        TkQualityIssue issue = new TkQualityIssue();
        issue.setReportId(report.getId());
        issue.setRuleId(completeness.getId());
        issue.setRuleName(completeness.getRuleName());
        issue.setRuleType("COMPLETENESS");
        issue.setTargetTable("tgt_enterprise_info");
        issue.setTargetField("contact_phone");
        issue.setIssueLevel("LOW");
        issue.setIssueDesc("质量规则【企业信息-联系电话完整性】检核未达标：8/120 行联系电话为空（占比 6.67%），得分 93.33");
        issue.setSampleData("{\"problemValue\":\"空值\",\"count\":8}");
        issue.setStatus("OPEN");
        issue.setFoundTime(LocalDateTime.now().minusHours(5));
        issueRepository.save(issue);

        completeness.setLastCheckTime(report.getCheckTime());
        completeness.setLastScore(93.33D);
        ruleRepository.save(completeness);
        log.info("质量规则与报告初始化完成：QR-ENT-COMPLETENESS / QR-ENT-UNIQUENESS");
    }

    // ====================== 辅助 ======================

    private long countOf(String table) {
        try {
            Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
            return count == null ? 0L : count;
        } catch (Exception e) {
            return 0L;
        }
    }

    private void saveVersion(TkTask task, String changeType, String note) {
        com.share.task.entity.TkTaskVersion version = new com.share.task.entity.TkTaskVersion();
        version.setTaskId(task.getId());
        version.setTaskCode(task.getTaskCode());
        version.setVersionNo(1);
        version.setChangeType(changeType);
        version.setChangeNote(note);
        version.setDefinitionJson(JsonUtil.toJson(task));
        versionRepository.save(version);
    }

    private MappingRule rule(String sourceField, String targetField, String transform) {
        MappingRule rule = new MappingRule();
        rule.setSourceField(sourceField);
        rule.setTargetField(targetField);
        rule.setTransform(transform);
        return rule;
    }

    private Map<String, Object> operator(String type, Map<String, Object> params) {
        Map<String, Object> operator = new LinkedHashMap<>();
        operator.put("type", type);
        operator.put("params", params);
        return operator;
    }

    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            params.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return params;
    }
}
