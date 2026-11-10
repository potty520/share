package com.share.agent.seed;

import com.share.agent.entity.AgCommand;
import com.share.agent.entity.AgHeartbeat;
import com.share.agent.entity.AgNode;
import com.share.agent.repository.AgCommandRepository;
import com.share.agent.repository.AgHeartbeatRepository;
import com.share.agent.repository.AgNodeRepository;
import com.share.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * share-agent 种子数据（@Order(31)）：2 个演示前置节点（1 在线 1 离线）、
 * 心跳历史记录、1 条待执行命令；跨域只读 tk_task 取演示任务作为命令负载（容错）。
 */
@Slf4j
@Component
@Order(31)
@RequiredArgsConstructor
public class AgentSeedRunner implements CommandLineRunner {

    private final AgNodeRepository nodeRepository;

    private final AgHeartbeatRepository heartbeatRepository;

    private final AgCommandRepository commandRepository;

    private final JdbcTemplate jdbcTemplate;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        if (nodeRepository.countByDeletedFalse() == 0) {
            seedNodes();
        }
        if (commandRepository.countByDeletedFalse() == 0) {
            seedCommand();
        }
        log.info("share-agent 种子数据检查完成：节点 {} 个，命令 {} 条",
                nodeRepository.countByDeletedFalse(), commandRepository.countByDeletedFalse());
    }

    private void seedNodes() {
        LocalDateTime now = LocalDateTime.now();
        AgNode online = new AgNode();
        online.setNodeCode("AG-NODE-2026001");
        online.setNodeName("财务部前置节点");
        online.setIp("10.20.1.11");
        online.setPort(9527);
        online.setCapabilitiesJson(JsonUtil.toJson(List.of("TASK_RUN", "FILE_TRANSFER", "TASK_STOP")));
        online.setNodeToken("AGENT-TOKEN-DEMO-2026001");
        online.setCertFingerprint("6F:1A:2B:3C:4D:5E:6F:70:81:92:A3:B4:C5:D6:E7:F8");
        online.setStatus("ONLINE");
        online.setVersion("1.4.2");
        online.setOsInfo("Windows Server 2019");
        online.setCpuUsage(32.5D);
        online.setMemUsage(61.2D);
        online.setDiskUsage(48.0D);
        online.setInstallCode("AGENT-INSTALL-2026");
        online.setLastHeartbeatTime(now.minusSeconds(12));
        online.setRegisterTime(now.minusDays(30));
        online.setTasksDone(28L);
        online.setRemark("财务部数据交换前置机");
        online = nodeRepository.save(online);

        AgNode offline = new AgNode();
        offline.setNodeCode("AG-NODE-2026002");
        offline.setNodeName("人力资源部前置节点");
        offline.setIp("10.20.2.21");
        offline.setPort(9527);
        offline.setCapabilitiesJson(JsonUtil.toJson(List.of("TASK_RUN", "FILE_TRANSFER")));
        offline.setNodeToken("AGENT-TOKEN-DEMO-2026002");
        offline.setCertFingerprint("9A:8B:7C:6D:5E:4F:30:21:12:03:F4:E5:D6:C7:B8:A9");
        offline.setStatus("OFFLINE");
        offline.setVersion("1.3.8");
        offline.setOsInfo("CentOS 7.9");
        offline.setCpuUsage(0D);
        offline.setMemUsage(0D);
        offline.setDiskUsage(0D);
        offline.setInstallCode("AGENT-INSTALL-2026");
        offline.setLastHeartbeatTime(now.minusDays(2));
        offline.setRegisterTime(now.minusDays(45));
        offline.setTasksDone(16L);
        offline.setRemark("人力资源部数据交换前置机（维护中）");
        nodeRepository.save(offline);

        // 在线节点最近 6 次心跳（30 秒间隔）
        double[] cpus = {30.2D, 31.8D, 33.1D, 32.5D, 31.0D, 32.5D};
        double[] mems = {60.1D, 60.8D, 61.5D, 61.2D, 60.9D, 61.2D};
        double[] disks = {47.9D, 47.9D, 48.0D, 48.0D, 48.0D, 48.0D};
        for (int i = 0; i < cpus.length; i++) {
            saveHeartbeat(online, cpus[i], mems[i], disks[i], "ONLINE",
                    now.minusSeconds((long) (cpus.length - 1 - i) * 30), "定时心跳");
        }
        // 离线节点下线前 3 次心跳
        for (int i = 0; i < 3; i++) {
            saveHeartbeat(offline, 18.0D + i, 45.0D + i, 39.0D + i, "ONLINE",
                    now.minusDays(2).minusMinutes((long) (3 - i) * 1), "下线前心跳");
        }
        log.info("前置节点初始化完成：AG-NODE-2026001（在线）、AG-NODE-2026002（离线）");
    }

    private void saveHeartbeat(AgNode node, Double cpu, Double mem, Double disk, String status,
                               LocalDateTime reportTime, String message) {
        AgHeartbeat heartbeat = new AgHeartbeat();
        heartbeat.setNodeCode(node.getNodeCode());
        heartbeat.setNodeName(node.getNodeName());
        heartbeat.setIp(node.getIp());
        heartbeat.setCpuUsage(cpu);
        heartbeat.setMemUsage(mem);
        heartbeat.setDiskUsage(disk);
        heartbeat.setStatus(status);
        heartbeat.setReportTime(reportTime);
        heartbeat.setMessage(message);
        heartbeatRepository.save(heartbeat);
    }

    /** 生成一条待执行命令：负载取真实任务信息（跨域只读 tk_task，失败则用默认负载）。 */
    private void seedCommand() {
        AgNode node = nodeRepository.findByNodeCodeAndDeletedFalse("AG-NODE-2026001").orElse(null);
        if (node == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", 1);
        payload.put("taskCode", "TASK-ENT-FULL");
        payload.put("taskName", "企业基础信息全量同步");
        payload.put("batchSize", 500);
        try {
            List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                    "select id, task_code, task_name from tk_task where deleted = false order by id asc");
            if (!tasks.isEmpty()) {
                Map<String, Object> task = tasks.get(0);
                payload.put("taskId", task.get("id"));
                payload.put("taskCode", task.get("task_code"));
                payload.put("taskName", task.get("task_name"));
            }
        } catch (Exception e) {
            log.debug("跨域读取 tk_task 失败（任务域可能尚未初始化）：{}", e.getMessage());
        }
        AgCommand command = new AgCommand();
        command.setCommandId("CMD202611090800000001");
        command.setNodeCode(node.getNodeCode());
        command.setNodeName(node.getNodeName());
        command.setCommandType("TASK_RUN");
        command.setTitle("执行企业基础信息全量同步");
        command.setPayloadJson(JsonUtil.toJson(payload));
        command.setStatus("PENDING");
        command.setPriority(8);
        command.setRetryCount(0);
        command.setCreateByName("admin");
        command.setExpireTime(LocalDateTime.now().plusHours(2));
        commandRepository.save(command);
        log.info("前置节点命令初始化完成：{}", command.getCommandId());
    }
}
