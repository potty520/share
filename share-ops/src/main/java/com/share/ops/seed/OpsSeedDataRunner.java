package com.share.ops.seed;

import com.share.common.util.JsonUtil;
import com.share.ops.audit.entity.AudOperationLog;
import com.share.ops.audit.repository.AudOperationLogRepository;
import com.share.ops.file.entity.FlFile;
import com.share.ops.file.entity.FlTransferTask;
import com.share.ops.file.repository.FlFileRepository;
import com.share.ops.file.repository.FlTransferTaskRepository;
import com.share.ops.file.service.FileStorageService;
import com.share.ops.monitor.entity.MonAlarmRecord;
import com.share.ops.monitor.entity.MonAlarmRule;
import com.share.ops.monitor.entity.MonMetricPoint;
import com.share.ops.monitor.entity.MonNode;
import com.share.ops.monitor.repository.MonAlarmRecordRepository;
import com.share.ops.monitor.repository.MonAlarmRuleRepository;
import com.share.ops.monitor.repository.MonMetricPointRepository;
import com.share.ops.monitor.repository.MonNodeRepository;
import com.share.ops.notify.entity.NotifyAnnouncement;
import com.share.ops.notify.entity.NotifyChannel;
import com.share.ops.notify.entity.NotifyFeedback;
import com.share.ops.notify.entity.NotifyMessage;
import com.share.ops.notify.repository.NotifyAnnouncementRepository;
import com.share.ops.notify.repository.NotifyChannelRepository;
import com.share.ops.notify.repository.NotifyFeedbackRepository;
import com.share.ops.notify.repository.NotifyMessageRepository;
import com.share.ops.support.OpsConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * share-ops 种子数据（FR-09/11/12/14）。
 *
 * <p>执行顺序 {@code @Order(50)}：auth(1) → catalog(20) → task(30) → share(40) → ops(50)。
 * 每个数据块先判断 {@code count()==0}，已存在则跳过；任一块失败只记日志，不影响应用启动。</p>
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class OpsSeedDataRunner implements CommandLineRunner {

    /** 演示用固定随机种子，保证多次部署报表数据形态稳定 */
    private static final Random RANDOM = new Random(20261109L);

    private final FlFileRepository fileRepository;
    private final FlTransferTaskRepository transferTaskRepository;
    private final AudOperationLogRepository auditLogRepository;
    private final MonNodeRepository nodeRepository;
    private final MonAlarmRuleRepository alarmRuleRepository;
    private final MonAlarmRecordRepository alarmRecordRepository;
    private final MonMetricPointRepository metricPointRepository;
    private final NotifyMessageRepository messageRepository;
    private final NotifyChannelRepository channelRepository;
    private final NotifyAnnouncementRepository announcementRepository;
    private final NotifyFeedbackRepository feedbackRepository;
    private final FileStorageService storage;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("share-ops 种子数据已关闭（share.seed.enabled=false）");
            return;
        }
        seedFiles();
        seedTransferTask();
        seedAuditLogs();
        seedNodes();
        seedAlarmRules();
        seedAlarmRecords();
        seedMessages();
        seedChannels();
        seedAnnouncements();
        seedFeedback();
        seedMetricHistory();
        log.info("share-ops 种子数据检查完成");
    }

    // ================= 演示文件 =================

    private void seedFiles() {
        try {
            if (fileRepository.countByDeletedFalse() > 0) {
                return;
            }
            Path demoDir = storage.root().resolve("demo");
            storage.ensureDir(demoDir);
            Path enterpriseCsv = demoDir.resolve("企业基础信息_20261109.csv");
            Path socialCsv = demoDir.resolve("社保参保信息_20261109.csv");
            writeText(enterpriseCsv, "统一社会信用代码,企业名称,法定代表人,登记状态,注册日期,所属行业\n"
                    + "91320100MA1K2X3Y4A,南京市云图信息技术有限公司,张伟,存续,2018-03-12,软件和信息技术服务业\n"
                    + "91320100MA1K5B6C7D,江苏恒通数据科技有限公司,李娜,存续,2019-07-25,数据服务\n"
                    + "91320100MA1K8E9F0G,南京市民生医药有限公司,王强,存续,2016-11-08,医药制造业\n"
                    + "91320100MA1K1H2J3K,江苏省智慧城市研究院,陈静,存续,2020-05-19,研究和试验发展\n");
            writeText(socialCsv, "公民身份号码,姓名,参保单位,险种,参保状态,缴费基数\n"
                    + "320102199001011234,张伟,南京市云图信息技术有限公司,职工基本养老保险,正常参保,8500.00\n"
                    + "320102199203054567,李娜,江苏恒通数据科技有限公司,职工基本医疗保险,正常参保,9200.00\n"
                    + "320102198807129876,王强,南京市民生医药有限公司,失业保险,正常参保,7600.00\n");
            saveDemoFile(enterpriseCsv, "provider", 2L, 2L, 2, "演示数据：企业基础信息导出文件");
            saveDemoFile(socialCsv, "provider", 2L, 4L, 3, "演示数据：社保参保信息导出文件（涉敏）");
            log.info("share-ops 种子：2 个演示文件已生成于 {}", demoDir);
        } catch (Exception e) {
            log.warn("share-ops 种子：演示文件生成失败: {}", e.getMessage());
        }
    }

    private void writeText(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void saveDemoFile(Path path, String uploader, Long uploaderId, Long deptId,
                              int secretLevel, String remark) {
        FlFile file = new FlFile();
        file.setFileName(path.getFileName().toString());
        file.setStoredName(path.getFileName().toString());
        file.setFilePath(storage.relative(path));
        file.setFileSize(storage.sizeOf(path));
        file.setContentType("text/csv");
        file.setExtName("csv");
        file.setMd5(storage.md5(path));
        file.setSha256(storage.sha256(path));
        file.setUploader(uploader);
        file.setUploaderId(uploaderId);
        file.setDeptId(deptId);
        file.setStatus(OpsConstants.FILE_STATUS_SUCCESS);
        file.setSecretLevel(secretLevel);
        file.setChunkCount(1);
        file.setBizType(OpsConstants.FILE_BIZ_DEMO);
        file.setRemark(remark);
        fileRepository.save(file);
    }

    // ================= 文件收发任务 =================

    private void seedTransferTask() {
        try {
            if (transferTaskRepository.countByDeletedFalse() > 0) {
                return;
            }
            Path demoDir = storage.root().resolve("demo");
            Path outDir = storage.root().resolve("transfer-out");
            Path watchDir = storage.root().resolve("watch-in");
            storage.ensureDir(demoDir);
            storage.ensureDir(outDir);
            storage.ensureDir(watchDir);

            FlTransferTask task = new FlTransferTask();
            task.setTaskName("每日数据文件下发（演示）");
            task.setSourceType("LOCAL");
            task.setSourcePath(demoDir.toString());
            task.setTargetType("LOCAL");
            task.setTargetPath(outDir.toString());
            task.setScheduleCron("0 0/30 * * * ?");
            task.setWatchPath(watchDir.toString());
            task.setEnabled(true);
            task.setTransferMode(OpsConstants.TRANSFER_MODE_DIR);
            task.setDeptId(9L);
            task.setRemark("每 30 分钟将演示目录批量下发至目标目录；watch-in 目录出现新文件时自动触发");
            task.setLastStatus(null);
            transferTaskRepository.save(task);
            log.info("share-ops 种子：1 个文件收发任务已创建");
        } catch (Exception e) {
            log.warn("share-ops 种子：文件收发任务创建失败: {}", e.getMessage());
        }
    }

    // ================= 审计日志 =================

    private void seedAuditLogs() {
        try {
            if (auditLogRepository.countByDeletedFalse() > 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            saveAuditLog("provider", 2L, "PROVIDER", "datasource", "DATASOURCE_CREATE", "DATASOURCE", "1",
                    "SUCCESS", null, now.minusDays(2).withHour(10).withMinute(12).withSecond(31),
                    "10.10.2.21", "POST", "/api/datasource/v1/sources", 186,
                    "{\"sourceName\":\"市税务局 MySQL\",\"sourceType\":\"MYSQL\",\"host\":\"10.10.2.15\"}");
            saveAuditLog("bizadmin", 6L, "BIZ_ADMIN", "catalog", "CATALOG_PUBLISH", "CATALOG", "12",
                    "SUCCESS", null, now.minusDays(1).withHour(15).withMinute(40).withSecond(8),
                    "10.10.1.33", "POST", "/api/catalog/v1/resources/12/audit", 92,
                    "{\"approved\":true,\"comment\":\"编目信息完整，同意发布\"}");
            saveAuditLog("ops", 5L, "OPS", "task", "TASK_TRIGGER", "TASK", "1",
                    "FAIL", "源端连接超时：DS-TAX-MYSQL 10.10.2.15:3306 connect timed out",
                    now.minusDays(1).withHour(3).withMinute(20).withSecond(45),
                    "10.10.1.9", "POST", "/api/task/v1/tasks/1/run", 30_215,
                    "{\"taskId\":1,\"triggerType\":\"MANUAL\"}");
            saveAuditLog("admin", 1L, "ADMIN", "approval", "APPROVAL_APPROVE", "APPLY", "AP20261108001",
                    "SUCCESS", null, now.minusHours(6).withMinute(5),
                    "10.10.1.2", "POST", "/api/approval/v1/applies/1/approve", 64,
                    "{\"applyId\":1,\"node\":\"业务管理员\",\"comment\":\"同意共享\"}");
            saveAuditLog("consumer", 3L, "CONSUMER", "file", "FILE_EXPORT", "FILE", "3",
                    "SUCCESS", null, now.minusDays(1).withHour(23).withMinute(15).withSecond(12),
                    "10.10.3.48", "GET", "/api/file/v1/files/3/download", 421,
                    "{\"fileId\":3,\"fileName\":\"企业基础信息_20261109.csv\"}");
            log.info("share-ops 种子：5 条审计日志已创建");
        } catch (Exception e) {
            log.warn("share-ops 种子：审计日志创建失败: {}", e.getMessage());
        }
    }

    private void saveAuditLog(String operatorName, Long operatorId, String roleCode, String module,
                              String action, String targetType, String targetId, String result,
                              String errorMsg, LocalDateTime operateTime, String ip, String method,
                              String uri, long costMs, String detail) {
        AudOperationLog log = new AudOperationLog();
        log.setOperatorName(operatorName);
        log.setOperatorId(operatorId);
        log.setRoleCode(roleCode);
        log.setModule(module);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setResult(result);
        log.setErrorMsg(errorMsg);
        log.setOperateTime(operateTime);
        log.setCreateTime(operateTime);
        log.setIp(ip);
        log.setRequestMethod(method);
        log.setUri(uri);
        log.setCostMs(costMs);
        log.setDetail(detail);
        log.setTraceId("seed" + Math.abs(operateTime.hashCode()));
        log.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) ShareExchangeDemo");
        auditLogRepository.save(log);
    }

    // ================= 节点监控 =================

    private void seedNodes() {
        try {
            if (nodeRepository.countByDeletedFalse() > 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            MonNode online = new MonNode();
            online.setNodeCode("EDGE-001");
            online.setNodeName("市税务局前置节点");
            online.setNodeType("EDGE");
            online.setIp("10.10.2.11");
            online.setPort(9443);
            online.setStatus(OpsConstants.NODE_ONLINE);
            online.setCpuUsage(32.6);
            online.setMemUsage(48.2);
            online.setDiskUsage(61.4);
            online.setNetIn(238.5);
            online.setNetOut(412.8);
            online.setVersion("1.0.0");
            online.setLastHeartbeat(now.minusSeconds(5));
            online.setDeptId(2L);
            online.setNodeGroup("税务交换域");
            online.setRunningTasks(1);
            online.setSimulated(true);
            online.setRemark("与 share-agent 种子节点一致（EDGE-001，在线）");
            nodeRepository.save(online);

            MonNode offline = new MonNode();
            offline.setNodeCode("EDGE-002");
            offline.setNodeName("市人社局前置节点");
            offline.setNodeType("EDGE");
            offline.setIp("10.10.4.12");
            offline.setPort(9443);
            offline.setStatus(OpsConstants.NODE_OFFLINE);
            offline.setCpuUsage(0.0);
            offline.setMemUsage(0.0);
            offline.setDiskUsage(72.1);
            offline.setNetIn(0.0);
            offline.setNetOut(0.0);
            offline.setVersion("1.0.0");
            offline.setLastHeartbeat(now.minusMinutes(20));
            offline.setDeptId(4L);
            offline.setNodeGroup("人社交换域");
            offline.setRunningTasks(0);
            offline.setSimulated(false);
            offline.setRemark("与 share-agent 种子节点一致（EDGE-002，离线）");
            nodeRepository.save(offline);
            log.info("share-ops 种子：2 个监控节点已创建（EDGE-001 在线 / EDGE-002 离线）");
        } catch (Exception e) {
            log.warn("share-ops 种子：节点创建失败: {}", e.getMessage());
        }
    }

    // ================= 告警规则 =================

    private void seedAlarmRules() {
        try {
            if (alarmRuleRepository.countByDeletedFalse() > 0) {
                return;
            }
            saveRule("交换任务失败率告警", OpsConstants.RULE_TASK_FAIL_RATE, "GT", 5.0, 300, "P1",
                    "INNER,EMAIL", "任务失败率超过 5% 时告警，排查源端连通性与字段映射");
            saveRule("前置节点离线告警", OpsConstants.RULE_NODE_OFFLINE, "GE", 1.0, 60, "P0",
                    "INNER,SMS,EMAIL", "前置节点心跳超时判定为离线，需立即处置");
            saveRule("开放 API 错误率告警", OpsConstants.RULE_API_ERROR_RATE, "GT", 3.0, 300, "P2",
                    "INNER,WEBHOOK", "共享服务调用错误率超过 3% 时告警");
            log.info("share-ops 种子：3 条告警规则已创建");
        } catch (Exception e) {
            log.warn("share-ops 种子：告警规则创建失败: {}", e.getMessage());
        }
    }

    private void saveRule(String name, String metricType, String operator, double threshold,
                          int durationSeconds, String level, String channels, String description) {
        MonAlarmRule rule = new MonAlarmRule();
        rule.setRuleName(name);
        rule.setMetricType(metricType);
        rule.setOperator(operator);
        rule.setThreshold(threshold);
        rule.setDurationSeconds(durationSeconds);
        rule.setAlarmLevel(level);
        rule.setChannels(channels);
        rule.setEnabled(true);
        rule.setDescription(description);
        alarmRuleRepository.save(rule);
    }

    // ================= 告警记录 =================

    private void seedAlarmRecords() {
        try {
            if (alarmRecordRepository.count() > 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            MonAlarmRule nodeRule = findRuleByName("前置节点离线告警");
            MonAlarmRule taskRule = findRuleByName("交换任务失败率告警");
            MonAlarmRule apiRule = findRuleByName("开放 API 错误率告警");

            MonAlarmRecord p0 = new MonAlarmRecord();
            p0.setRuleId(nodeRule == null ? null : nodeRule.getId());
            p0.setRuleName("前置节点离线告警");
            p0.setMetricType(OpsConstants.RULE_NODE_OFFLINE);
            p0.setAlarmLevel("P0");
            p0.setMetricValue(1.0);
            p0.setThresholdValue(1.0);
            p0.setContent("前置节点 EDGE-002（市人社局前置节点）心跳超时已离线，最后心跳时间 "
                    + now.minusMinutes(20) + "，请检查节点网络与 Agent 进程状态。");
            p0.setStatus(OpsConstants.ALARM_OPEN);
            p0.setObjectType(OpsConstants.DIM_NODE);
            p0.setObjectCode("EDGE-002");
            p0.setFirstTime(now.minusMinutes(40));
            p0.setLastTime(now.minusMinutes(10));
            p0.setTriggerCount(3);
            p0.setNotifyStatus(OpsConstants.SEND_SENT);
            alarmRecordRepository.save(p0);

            MonAlarmRecord p1 = new MonAlarmRecord();
            p1.setRuleId(taskRule == null ? null : taskRule.getId());
            p1.setRuleName("交换任务失败率告警");
            p1.setMetricType(OpsConstants.RULE_TASK_FAIL_RATE);
            p1.setAlarmLevel("P1");
            p1.setMetricValue(12.5);
            p1.setThresholdValue(5.0);
            p1.setContent("任务「企业基础信息全量同步」最近 1 小时执行 8 次、失败 1 次，失败率 12.5%（阈值 5%）。");
            p1.setStatus(OpsConstants.ALARM_ACKED);
            p1.setObjectType(OpsConstants.DIM_TASK);
            p1.setObjectCode("1");
            p1.setFirstTime(now.minusHours(3));
            p1.setLastTime(now.minusHours(2));
            p1.setTriggerCount(1);
            p1.setNotifyStatus(OpsConstants.SEND_SENT);
            p1.setAckBy("ops");
            p1.setAckTime(now.minusHours(2));
            p1.setAckRemark("已确认：源端网络抖动导致超时，已重跑成功");
            alarmRecordRepository.save(p1);

            MonAlarmRecord p2 = new MonAlarmRecord();
            p2.setRuleId(apiRule == null ? null : apiRule.getId());
            p2.setRuleName("开放 API 错误率告警");
            p2.setMetricType(OpsConstants.RULE_API_ERROR_RATE);
            p2.setAlarmLevel("P2");
            p2.setMetricValue(3.8);
            p2.setThresholdValue(3.0);
            p2.setContent("开放 API 最近 2 小时调用 1,286 次、失败 49 次，错误率 3.8%（阈值 3%），"
                    + "请检查上游数据源与凭据有效期。");
            p2.setStatus(OpsConstants.ALARM_OPEN);
            p2.setObjectType("API");
            p2.setObjectCode("ALL");
            p2.setFirstTime(now.minusHours(2));
            p2.setLastTime(now.minusMinutes(15));
            p2.setTriggerCount(2);
            p2.setNotifyStatus(OpsConstants.SEND_SENT);
            alarmRecordRepository.save(p2);
            log.info("share-ops 种子：3 条告警记录已创建");
        } catch (Exception e) {
            log.warn("share-ops 种子：告警记录创建失败: {}", e.getMessage());
        }
    }

    private MonAlarmRule findRuleByName(String name) {
        return alarmRuleRepository.findAllByDeletedFalseOrderByCreateTimeDesc().stream()
                .filter(rule -> name.equals(rule.getRuleName()))
                .findFirst().orElse(null);
    }

    // ================= 站内消息 =================

    private void seedMessages() {
        try {
            if (messageRepository.countByDeletedFalse() > 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            saveMessage(1L, "admin 张管理", "您有新的共享申请待审批",
                    "使用方提交了《企业基础信息查询》共享申请，申请期限 90 天，请登录审批中心处理。",
                    "APPROVAL", "AP20261109001", OpsConstants.MSG_CHANNEL_INNER, "INFO",
                    "/approval/todo", false, now.minusMinutes(25));
            saveMessage(1L, "admin 张管理", "[P0] 前置节点离线",
                    "前置节点 EDGE-002（市人社局前置节点）心跳超时已离线，请立即检查网络与 Agent 进程。",
                    "ALARM", "1", OpsConstants.MSG_CHANNEL_INNER, "ERROR",
                    "/monitor/alarms", false, now.minusMinutes(40));
            saveMessage(1L, "admin 张管理", "2 个调用凭据将在 7 天内到期",
                    "凭据 CR20261102001、CR20261103002 将于 2026-11-16 到期，请及时通知使用方续期或回收。",
                    "EXPIRY", "CR20261102001", OpsConstants.MSG_CHANNEL_INNER, "WARN",
                    "/share/credentials", false, now.minusHours(3));
            saveMessage(1L, "admin 张管理", "平台公告：共享交换平台 V1.0 试运行",
                    "共享交换平台 V1.0 已进入试运行阶段，请各部门按规范完成数据目录编目与共享申请。",
                    "ANNOUNCEMENT", "1", OpsConstants.MSG_CHANNEL_INNER, "INFO",
                    "/notify/announcements", false, now.minusHours(5));
            saveMessage(1L, "admin 张管理", "任务执行完成",
                    "任务「企业基础信息全量同步」执行成功，批次 SW20261109101000123，同步 4,286 行，耗时 3.2 秒。",
                    "TASK", "SW20261109101000123", OpsConstants.MSG_CHANNEL_INNER, "INFO",
                    "/task/instances", true, now.minusHours(8));
            saveMessage(3L, "consumer 李使用", "您的共享申请已通过",
                    "申请《社保参保信息查询》已通过审批，凭据已签发，可前往共享服务页面查看调用文档。",
                    "APPROVAL", "AP20261108003", OpsConstants.MSG_CHANNEL_INNER, "INFO",
                    "/approval/my", false, now.minusHours(6));
            log.info("share-ops 种子：6 条站内消息已创建（admin 4 条未读）");
        } catch (Exception e) {
            log.warn("share-ops 种子：站内消息创建失败: {}", e.getMessage());
        }
    }

    private void saveMessage(Long receiverId, String receiverName, String title, String content,
                             String bizType, String bizId, String channel, String level, String link,
                             boolean read, LocalDateTime createTime) {
        NotifyMessage message = new NotifyMessage();
        message.setReceiverId(receiverId);
        message.setReceiverName(receiverName);
        message.setTitle(title);
        message.setContent(content);
        message.setBizType(bizType);
        message.setBizId(bizId);
        message.setChannel(channel);
        message.setMsgLevel(level);
        message.setLink(link);
        message.setReadFlag(read);
        message.setReadTime(read ? createTime.plusMinutes(10) : null);
        message.setSendStatus(OpsConstants.SEND_SKIPPED);
        message.setSource("SEED");
        message.setCreateTime(createTime);
        messageRepository.save(message);
    }

    // ================= 通知通道 =================

    private void seedChannels() {
        try {
            if (channelRepository.countByDeletedFalse() > 0) {
                return;
            }
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("url", "http://127.0.0.1:9999/mock-webhook");
            config.put("method", "POST");
            config.put("token", "");
            NotifyChannel webhook = new NotifyChannel();
            webhook.setChannelCode("WEBHOOK-DEMO");
            webhook.setChannelName("演示 Webhook 通道");
            webhook.setChannelType(OpsConstants.CHANNEL_TYPE_WEBHOOK);
            webhook.setConfigJson(JsonUtil.toJson(config));
            webhook.setEnabled(true);
            webhook.setRemark("演示用 IM 机器人 Webhook（无接收端时发送失败仅记日志，不阻塞业务）");
            channelRepository.save(webhook);

            Map<String, Object> emailConfig = new LinkedHashMap<>();
            emailConfig.put("host", "smtp.example.gov.cn");
            emailConfig.put("port", 465);
            emailConfig.put("username", "share-exchange");
            emailConfig.put("password", "change-me");
            emailConfig.put("from", "share-exchange@example.gov.cn");
            emailConfig.put("defaultTo", "ops@example.gov.cn");
            emailConfig.put("ssl", true);
            NotifyChannel email = new NotifyChannel();
            email.setChannelCode("EMAIL-DEMO");
            email.setChannelName("演示邮件通道");
            email.setChannelType(OpsConstants.CHANNEL_TYPE_EMAIL);
            email.setConfigJson(JsonUtil.toJson(emailConfig));
            email.setEnabled(false);
            email.setRemark("演示邮件通道（默认停用，配置真实 SMTP 后启用）");
            channelRepository.save(email);
            log.info("share-ops 种子：通知通道已创建");
        } catch (Exception e) {
            log.warn("share-ops 种子：通知通道创建失败: {}", e.getMessage());
        }
    }

    // ================= 公告 =================

    private void seedAnnouncements() {
        try {
            if (announcementRepository.countByDeletedFalse() > 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            NotifyAnnouncement announcement = new NotifyAnnouncement();
            announcement.setTitle("共享交换平台 V1.0 试运行公告");
            announcement.setContent("各部门：\n"
                    + "共享交换平台 V1.0 已完成部署并进入试运行阶段。即日起，数据共享申请、交换任务编排、"
                    + "共享服务调用等能力正式开放试用。\n"
                    + "一、试运行范围：市大数据局、市税务局、市市场监管局、市人社局。\n"
                    + "二、请各数据提供方在 11 月 20 日前完成本部门数据目录编目与挂接。\n"
                    + "三、涉敏数据共享须经安全管理员审批，并默认开启脱敏策略。\n"
                    + "四、试运行期间如遇问题，请联系平台运维部（内线 8001）。\n"
                    + "特此公告。");
            announcement.setPublisherId(1L);
            announcement.setPublisherName("admin");
            announcement.setAnnLevel("IMPORTANT");
            announcement.setStatus(OpsConstants.ANN_PUBLISHED);
            announcement.setTopFlag(true);
            announcement.setPublishTime(now.minusDays(2));
            announcement.setExpireTime(now.plusDays(30));
            announcement.setReadCount(12);
            announcement.setCreateTime(now.minusDays(2));
            announcementRepository.save(announcement);
            log.info("share-ops 种子：1 条公告已创建");
        } catch (Exception e) {
            log.warn("share-ops 种子：公告创建失败: {}", e.getMessage());
        }
    }

    // ================= 资源使用反馈 =================

    private void seedFeedback() {
        try {
            if (feedbackRepository.countByDeletedFalse() > 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            NotifyFeedback feedback = new NotifyFeedback();
            feedback.setResourceType("CATALOG");
            feedback.setResourceId("1");
            feedback.setResourceName("企业基础信息");
            feedback.setRating(5);
            feedback.setContent("数据字段完整、更新及时，申请到授权只用了半天，体验很好。");
            feedback.setUserId(3L);
            feedback.setUserName("consumer");
            feedback.setDeptId(1L);
            feedback.setStatus(OpsConstants.FB_OPEN);
            feedback.setCreateTime(now.minusDays(1));
            feedbackRepository.save(feedback);

            NotifyFeedback replied = new NotifyFeedback();
            replied.setResourceType("CATALOG");
            replied.setResourceId("2");
            replied.setResourceName("社保参保信息");
            replied.setRating(4);
            replied.setContent("参保状态字段偶尔有延迟，建议增加更新时间戳字段。");
            replied.setUserId(3L);
            replied.setUserName("consumer");
            replied.setDeptId(1L);
            replied.setReply("已确认问题，将在下一版本增加 update_time 字段并优化增量同步策略。");
            replied.setReplyBy("provider");
            replied.setReplyTime(now.minusHours(12));
            replied.setStatus(OpsConstants.FB_REPLIED);
            replied.setCreateTime(now.minusDays(2));
            feedbackRepository.save(replied);
            log.info("share-ops 种子：2 条资源使用反馈已创建");
        } catch (Exception e) {
            log.warn("share-ops 种子：反馈创建失败: {}", e.getMessage());
        }
    }

    // ================= 历史指标点 =================

    /**
     * 生成近 3 天每小时指标点（交换/API/节点），让监控趋势与运维报表有真实形态的数据。
     */
    private void seedMetricHistory() {
        try {
            if (metricPointRepository.countByDeletedFalse() > 0) {
                return;
            }
            LocalDateTime startHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(71);
            long[] taskIds = {1L, 2L, 3L};
            long[] deptIds = {2L, 3L, 4L};
            for (int i = 0; i < 72; i++) {
                LocalDateTime hour = startHour.plusHours(i);
                for (long taskId : taskIds) {
                    long rows = 800L + RANDOM.nextInt(3200);
                    long sample = 1 + RANDOM.nextInt(3);
                    long fail = RANDOM.nextInt(12) == 0 ? 1 : 0;
                    saveExchangePoint(OpsConstants.DIM_TASK, String.valueOf(taskId), hour,
                            rows, sample, sample - fail, fail);
                }
                for (long deptId : deptIds) {
                    long rows = 600L + RANDOM.nextInt(2800);
                    saveExchangePoint(OpsConstants.DIM_DEPT, String.valueOf(deptId), hour,
                            rows, 1, 1, 0);
                }
                long calls = 120L + RANDOM.nextInt(420);
                long apiFail = Math.round(calls * (RANDOM.nextInt(60) / 1000.0));
                saveSnapshotPoint(OpsConstants.METRIC_API, OpsConstants.METRIC_API_CALL,
                        OpsConstants.DIM_ALL, "ALL", hour, calls, calls, calls - apiFail, apiFail);
                long online = 1 + RANDOM.nextInt(2);
                saveSnapshotPoint(OpsConstants.METRIC_NODE, OpsConstants.METRIC_NODE_ONLINE,
                        OpsConstants.DIM_ALL, "ALL", hour, online, 2, online, 2 - online);
            }
            log.info("share-ops 种子：72 小时指标历史已生成（约 {} 条）", 72 * 8);
        } catch (Exception e) {
            log.warn("share-ops 种子：指标历史生成失败: {}", e.getMessage());
        }
    }

    private void saveExchangePoint(String dimensionType, String dimensionKey, LocalDateTime hour,
                                   long rows, long sample, long success, long fail) {
        MonMetricPoint point = new MonMetricPoint();
        point.setMetricType(OpsConstants.METRIC_EXCHANGE);
        point.setMetricName(OpsConstants.METRIC_EXCHANGE_BATCH);
        point.setDimensionType(dimensionType);
        point.setDimensionKey(dimensionKey);
        point.setStatTime(hour);
        point.setMetricValue((double) rows);
        point.setSampleCount(sample);
        point.setSuccessCount(success);
        point.setFailCount(fail);
        point.setTotalRowCount(rows);
        point.setTotalCostMs(600L + RANDOM.nextInt(4000));
        metricPointRepository.save(point);
    }

    private void saveSnapshotPoint(String metricType, String metricName, String dimensionType,
                                   String dimensionKey, LocalDateTime hour, double value,
                                   long sample, long success, long fail) {
        MonMetricPoint point = new MonMetricPoint();
        point.setMetricType(metricType);
        point.setMetricName(metricName);
        point.setDimensionType(dimensionType);
        point.setDimensionKey(dimensionKey);
        point.setStatTime(hour);
        point.setMetricValue(value);
        point.setSampleCount(sample);
        point.setSuccessCount(success);
        point.setFailCount(fail);
        point.setTotalRowCount(0L);
        point.setTotalCostMs(0L);
        metricPointRepository.save(point);
    }
}
