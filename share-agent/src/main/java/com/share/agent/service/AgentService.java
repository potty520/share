package com.share.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.share.agent.dto.AgentAckRequest;
import com.share.agent.dto.AgentChunkRequest;
import com.share.agent.dto.AgentDispatchRequest;
import com.share.agent.dto.AgentHeartbeatRequest;
import com.share.agent.dto.AgentPackageRequest;
import com.share.agent.dto.AgentRegisterRequest;
import com.share.agent.entity.AgCommand;
import com.share.agent.entity.AgHeartbeat;
import com.share.agent.entity.AgNode;
import com.share.agent.repository.AgCommandRepository;
import com.share.agent.repository.AgHeartbeatRepository;
import com.share.agent.repository.AgNodeRepository;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.CryptoUtil;
import com.share.common.util.IdGen;
import com.share.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前置节点 Agent 服务（FR-02 前置节点）：
 * 安装凭证注册、心跳、命令长轮询、执行结果回传、分片上传、离线摆渡包导入、节点管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** 演示环境固定安装凭证 */
    public static final String INSTALL_CODE = "AGENT-INSTALL-2026";

    /** 摆渡包签名密钥（演示固定值，正式环境应配置到密钥管理） */
    private static final String FERRY_SECRET = "share-agent-ferry-2026";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgNodeRepository nodeRepository;

    private final AgHeartbeatRepository heartbeatRepository;

    private final AgCommandRepository commandRepository;

    private final JdbcTemplate jdbcTemplate;

    /** 分片上传会话（演示环境内存态；正式环境应落 Redis/DB） */
    private final Map<String, UploadSession> uploadSessions = new ConcurrentHashMap<>();

    /** 摆渡包导入结果（内存索引 + 文件落盘 + ag_command 留痕） */
    private final Map<String, Map<String, Object>> importResults = new ConcurrentHashMap<>();

    /** 心跳超时秒数（超过判离线） */
    @Value("${share.monitor.heartbeat-timeout-seconds:90}")
    private long heartbeatTimeoutSeconds;

    @Value("${share.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    /** 文件落盘根目录 */
    @Value("${share.file.root:./data/files}")
    private String fileRoot;

    // ====================== 注册 / 心跳 ======================

    /** 安装凭证注册：下发节点身份、访问令牌与证书指纹（无需 JWT）。 */
    @Transactional
    public Map<String, Object> register(AgentRegisterRequest request) {
        if (request == null || request.getInstallCode() == null
                || !INSTALL_CODE.equals(request.getInstallCode().trim())) {
            throw new BizException(ErrorCode.FORBIDDEN, "安装凭证无效，拒绝注册");
        }
        if (request.getNodeName() == null || request.getNodeName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "nodeName 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        String token = IdGen.secret(48);
        // 同名节点重复注册时复用节点身份并轮换令牌，避免产生僵尸节点
        Optional<AgNode> existing = nodeRepository.findByDeletedFalseOrderByIdAsc().stream()
                .filter(node -> request.getNodeName().trim().equals(node.getNodeName()))
                .findFirst();
        AgNode node;
        String nodeCode;
        if (existing.isPresent()) {
            node = existing.get();
            nodeCode = node.getNodeCode();
            log.info("节点重新注册：{}（{}）", node.getNodeName(), nodeCode);
        } else {
            nodeCode = "AG" + now.format(TS) + String.format("%03d", RANDOM.nextInt(1000));
            node = new AgNode();
            node.setNodeCode(nodeCode);
            node.setRegisterTime(now);
            node.setTasksDone(0L);
        }
        node.setNodeName(request.getNodeName().trim());
        node.setIp(request.getIp());
        node.setPort(request.getPort());
        node.setVersion(request.getVersion());
        node.setOsInfo(request.getOsInfo());
        node.setInstallCode(request.getInstallCode().trim());
        node.setNodeToken(token);
        node.setCertFingerprint(buildFingerprint(nodeCode, token));
        node.setStatus("ONLINE");
        node.setLastHeartbeatTime(now);
        node.setCapabilitiesJson(JsonUtil.toJson(request.getCapabilities() == null
                ? List.of("TASK_RUN", "FILE_TRANSFER") : request.getCapabilities()));
        node.setCpuUsage(0D);
        node.setMemUsage(0D);
        node.setDiskUsage(0D);
        node = nodeRepository.save(node);
        saveHeartbeat(node, node.getCpuUsage(), node.getMemUsage(), node.getDiskUsage(), "注册上报", now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.getNodeCode());
        result.put("nodeCode", node.getNodeCode());
        result.put("nodeName", node.getNodeName());
        result.put("nodeToken", token);
        result.put("certFingerprint", node.getCertFingerprint());
        result.put("status", node.getStatus());
        result.put("registerTime", now);
        result.put("heartbeatIntervalSeconds", 30);
        result.put("pollTimeoutSeconds", 20);
        return result;
    }

    /** 心跳上报：更新节点资源与存活状态，并记录心跳历史。 */
    @Transactional
    public Map<String, Object> heartbeat(AgentHeartbeatRequest request) {
        AgNode node = requireNode(request == null ? null : request.getNodeId());
        validateToken(node, request == null ? null : request.getNodeToken());
        LocalDateTime now = LocalDateTime.now();
        String status = request.getStatus() == null || request.getStatus().isBlank()
                ? "ONLINE" : request.getStatus().toUpperCase();
        node.setCpuUsage(request.getCpu() == null ? node.getCpuUsage() : request.getCpu());
        node.setMemUsage(request.getMem() == null ? node.getMemUsage() : request.getMem());
        node.setDiskUsage(request.getDisk() == null ? node.getDiskUsage() : request.getDisk());
        node.setStatus("OFFLINE".equals(status) ? "OFFLINE" : "ONLINE");
        node.setLastHeartbeatTime(now);
        nodeRepository.save(node);
        saveHeartbeat(node, node.getCpuUsage(), node.getMemUsage(), node.getDiskUsage(), request.getMessage(), now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("nodeId", node.getNodeCode());
        result.put("status", node.getStatus());
        result.put("serverTime", now);
        result.put("pendingCommands", commandRepository.countByNodeCodeAndStatusAndDeletedFalse(node.getNodeCode(), "PENDING"));
        return result;
    }

    // ====================== 命令通道 ======================

    /**
     * 长轮询拉取待执行命令：无命令时最多等待 timeoutSeconds（上限 20 秒），
     * 期间每 500ms 检查一次；命令下发后置为 SENT，节点 ACK 后置为 SUCCESS/FAILED。
     */
    public Map<String, Object> pollCommands(String nodeId, String nodeToken, int timeoutSeconds) {
        AgNode node = requireNode(nodeId);
        validateToken(node, nodeToken);
        resetStaleCommands(node.getNodeCode());
        int waitSeconds = Math.max(0, Math.min(20, timeoutSeconds <= 0 ? 20 : timeoutSeconds));
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        List<AgCommand> pending = new ArrayList<>();
        while (true) {
            pending = commandRepository.findByNodeCodeAndStatusAndDeletedFalseOrderByPriorityDescIdAsc(
                    node.getNodeCode(), "PENDING");
            if (!pending.isEmpty() || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> commands = new ArrayList<>();
        for (AgCommand command : pending) {
            command.setStatus("SENT");
            command.setSendTime(now);
            command.setRetryCount(command.getRetryCount() == null ? 1 : command.getRetryCount() + 1);
            commandRepository.save(command);
            commands.add(commandPayload(command));
        }
        node.setLastHeartbeatTime(now);
        nodeRepository.save(node);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.getNodeCode());
        result.put("nodeName", node.getNodeName());
        result.put("serverTime", now);
        result.put("waitSeconds", waitSeconds);
        result.put("commands", commands);
        return result;
    }

    /** 命令执行结果回传。 */
    @Transactional
    public Map<String, Object> ack(String commandId, AgentAckRequest request) {
        AgNode node = requireNode(request == null ? null : request.getNodeId());
        validateToken(node, request == null ? null : request.getNodeToken());
        AgCommand command = commandRepository.findByCommandIdAndDeletedFalse(commandId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "命令不存在：" + commandId));
        if (!node.getNodeCode().equals(command.getNodeCode())) {
            throw new BizException(ErrorCode.FORBIDDEN, "命令不属于该节点");
        }
        boolean success = request.getSuccess() == null || request.getSuccess();
        command.setStatus(success ? "SUCCESS" : "FAILED");
        command.setAckTime(LocalDateTime.now());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", request.getMessage());
        if (request.getResult() != null) {
            result.put("data", request.getResult());
        }
        command.setResultJson(JsonUtil.toJson(result));
        commandRepository.save(command);
        node.setTasksDone((node.getTasksDone() == null ? 0L : node.getTasksDone()) + (success ? 1L : 0L));
        nodeRepository.save(node);
        log.info("节点 {} 回传命令 {} 结果：{}", node.getNodeCode(), commandId, command.getStatus());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("commandId", commandId);
        response.put("status", command.getStatus());
        response.put("ackTime", command.getAckTime());
        return response;
    }

    /** 管理端下发命令。 */
    @Transactional
    public Map<String, Object> dispatch(AgentDispatchRequest request, Long operatorId, String operatorName) {
        AgNode node = requireNode(request == null ? null : request.getNodeId());
        if ("OFFLINE".equalsIgnoreCase(node.getStatus())) {
            log.warn("向离线节点 {} 下发命令，将在节点上线后投递", node.getNodeCode());
        }
        AgCommand command = new AgCommand();
        command.setCommandId("CMD" + LocalDateTime.now().format(TS) + String.format("%04d", RANDOM.nextInt(10000)));
        command.setNodeCode(node.getNodeCode());
        command.setNodeName(node.getNodeName());
        command.setCommandType(request.getCommandType() == null || request.getCommandType().isBlank()
                ? "TASK_RUN" : request.getCommandType().toUpperCase());
        command.setTitle(request.getTitle() == null || request.getTitle().isBlank()
                ? "中心下发任务命令" : request.getTitle());
        command.setPayloadJson(JsonUtil.toJson(request.getPayload() == null ? Map.of() : request.getPayload()));
        command.setStatus("PENDING");
        command.setPriority(request.getPriority() == null ? 5 : request.getPriority());
        command.setRetryCount(0);
        command.setCreateByUser(operatorId);
        command.setCreateByName(operatorName);
        command.setExpireTime(LocalDateTime.now().plusMinutes(
                request.getExpireMinutes() == null ? 30 : Math.max(1, request.getExpireMinutes())));
        command = commandRepository.save(command);
        log.info("下发命令 {} 到节点 {}", command.getCommandId(), node.getNodeCode());
        return commandPayload(command);
    }

    // ====================== 分片上传 / 摆渡导入 ======================

    /** 分片上传：校验分片 MD5 后落盘，收齐后合并并计算整包 MD5。 */
    public Map<String, Object> uploadChunk(AgentChunkRequest request) {
        if (request == null || request.getData() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分片数据不能为空");
        }
        AgNode node = requireNode(request.getNodeId());
        validateToken(node, request.getNodeToken());
        if (request.getFileName() == null || request.getFileName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "fileName 不能为空");
        }
        if (request.getChunkNo() == null || request.getTotalChunks() == null
                || request.getChunkNo() < 1 || request.getTotalChunks() < 1
                || request.getChunkNo() > request.getTotalChunks()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分片序号/总数不合法");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(request.getData());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分片数据不是合法的 Base64");
        }
        String chunkMd5 = CryptoUtil.md5(data);
        if (request.getChunkMd5() != null && !request.getChunkMd5().isBlank()
                && !chunkMd5.equalsIgnoreCase(request.getChunkMd5().trim())) {
            throw new BizException(ErrorCode.FILE_CHUNK_MISMATCH,
                    "分片 MD5 校验失败：期望 " + request.getChunkMd5() + "，实际 " + chunkMd5);
        }
        String uploadId = request.getUploadId() == null || request.getUploadId().isBlank()
                ? "UP" + IdGen.shortId() : request.getUploadId().trim();
        UploadSession session = uploadSessions.computeIfAbsent(uploadId,
                key -> new UploadSession(uploadId, request.getFileName(), request.getTotalChunks(), node.getNodeCode()));
        Path dir = uploadDir().resolve(uploadId);
        try {
            Files.createDirectories(dir);
            Path chunkPath = dir.resolve(String.format("chunk-%05d.part", request.getChunkNo()));
            Files.write(chunkPath, data);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "分片落盘失败：" + e.getMessage());
        }
        session.received.add(request.getChunkNo());
        boolean completed = session.received.size() >= session.totalChunks;
        String fileMd5 = null;
        String filePath = null;
        long bytes = 0L;
        if (completed) {
            try {
                Path merged = dir.resolve(request.getFileName());
                try (var outputStream = Files.newOutputStream(merged)) {
                    for (int i = 1; i <= session.totalChunks; i++) {
                        Path chunk = dir.resolve(String.format("chunk-%05d.part", i));
                        if (!Files.exists(chunk)) {
                            throw new IOException("缺少分片 " + i);
                        }
                        outputStream.write(Files.readAllBytes(chunk));
                    }
                }
                filePath = merged.toString();
                bytes = Files.size(merged);
                fileMd5 = CryptoUtil.md5(Files.readAllBytes(merged));
                if (request.getFileMd5() != null && !request.getFileMd5().isBlank()
                        && !fileMd5.equalsIgnoreCase(request.getFileMd5().trim())) {
                    throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH,
                            "整包 MD5 校验失败：期望 " + request.getFileMd5() + "，实际 " + fileMd5);
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("uploadId", uploadId);
                meta.put("nodeId", node.getNodeCode());
                meta.put("fileName", request.getFileName());
                meta.put("totalChunks", session.totalChunks);
                meta.put("fileMd5", fileMd5);
                meta.put("bytes", bytes);
                meta.put("completedAt", LocalDateTime.now().toString());
                Files.writeString(dir.resolve("meta.json"), JsonUtil.toJson(meta), StandardCharsets.UTF_8);
            } catch (BizException bizException) {
                throw bizException;
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL, "分片合并失败：" + e.getMessage());
            } finally {
                uploadSessions.remove(uploadId);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadId", uploadId);
        result.put("nodeId", node.getNodeCode());
        result.put("fileName", request.getFileName());
        result.put("chunkNo", request.getChunkNo());
        result.put("chunkMd5", chunkMd5);
        result.put("receivedChunks", session.received.size());
        result.put("totalChunks", session.totalChunks);
        result.put("completed", completed);
        result.put("filePath", filePath);
        result.put("fileMd5", fileMd5);
        result.put("bytes", bytes);
        return result;
    }

    /**
     * 离线摆渡包导入：校验签名（SHA-256(packageName|checksum|manifestJson|secret)）与清单，
     * 导出的清单落盘并在 ag_command 留痕，形成可追溯的导入记录。
     */
    @Transactional
    public Map<String, Object> importPackage(AgentPackageRequest request) {
        if (request == null || request.getPackageName() == null || request.getChecksum() == null
                || request.getManifestJson() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "摆渡包 packageName/checksum/manifestJson 不能为空");
        }
        String expected = IdGen.sha256(request.getPackageName() + "|" + request.getChecksum() + "|"
                + request.getManifestJson() + "|" + FERRY_SECRET);
        if (request.getSignature() == null || !expected.equalsIgnoreCase(request.getSignature().trim())) {
            throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH, "摆渡包签名校验失败，拒绝导入");
        }
        List<Map<String, Object>> manifest;
        try {
            manifest = JsonUtil.fromJson(request.getManifestJson(), new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "摆渡包清单不是合法的 JSON 数组");
        }
        String packageId = request.getPackageId() == null || request.getPackageId().isBlank()
                ? "PKG" + IdGen.shortId() : request.getPackageId().trim();
        String nodeCode = null;
        if (request.getNodeId() != null && !request.getNodeId().isBlank()) {
            Optional<AgNode> node = nodeRepository.findByNodeCodeAndDeletedFalse(request.getNodeId().trim());
            nodeCode = node.map(AgNode::getNodeCode).orElse(request.getNodeId().trim());
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("packageId", packageId);
        record.put("packageName", request.getPackageName());
        record.put("nodeId", nodeCode);
        record.put("checksum", request.getChecksum());
        record.put("signature", request.getSignature());
        record.put("manifestHash", IdGen.sha256(request.getManifestJson()));
        record.put("fileCount", manifest.size());
        record.put("rowCount", manifest.stream().mapToLong(item -> toLong(item.get("rows"))).sum());
        record.put("verified", true);
        record.put("importedAt", now.toString());
        record.put("manifest", manifest);
        try {
            Path dir = importDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(packageId + ".json"), JsonUtil.toJson(record), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "摆渡包清单落盘失败：" + e.getMessage());
        }
        importResults.put(packageId, record);

        // 导入结果落库留痕（ag_command，状态 SUCCESS，不会被再次下发）
        AgCommand command = new AgCommand();
        command.setCommandId("IMP" + now.format(TS) + String.format("%04d", RANDOM.nextInt(10000)));
        command.setNodeCode(nodeCode);
        command.setCommandType("PACKAGE_IMPORT");
        command.setTitle("离线摆渡包导入：" + request.getPackageName());
        command.setPayloadJson(JsonUtil.toJson(Map.of(
                "packageId", packageId,
                "checksum", request.getChecksum(),
                "manifestHash", record.get("manifestHash"))));
        command.setStatus("SUCCESS");
        command.setPriority(5);
        command.setSendTime(now);
        command.setAckTime(now);
        command.setResultJson(JsonUtil.toJson(Map.of("verified", true, "fileCount", manifest.size(),
                "rowCount", record.get("rowCount"))));
        command.setCreateByName("ferry-import");
        commandRepository.save(command);
        log.info("离线摆渡包导入成功：{}（{} 个文件）", packageId, manifest.size());
        Map<String, Object> result = new LinkedHashMap<>(record);
        result.remove("manifest");
        return result;
    }

    // ====================== 节点管理 ======================

    /** 节点分页列表。 */
    public Map<String, Object> pageNodes(int pageNum, int pageSize, String keyword, String status) {
        List<AgNode> all = nodeRepository.findByDeletedFalseOrderByIdAsc();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (AgNode node : all) {
            if (keyword != null && !keyword.isBlank()) {
                String text = keyword.trim().toLowerCase();
                if (!contains(node.getNodeName(), text) && !contains(node.getNodeCode(), text)
                        && !contains(node.getIp(), text)) {
                    continue;
                }
            }
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(node.getStatus())) {
                continue;
            }
            filtered.add(nodeVo(node));
        }
        int normalizedPageNum = Math.max(1, pageNum);
        int normalizedPageSize = Math.max(1, Math.min(200, pageSize));
        int from = Math.min((normalizedPageNum - 1) * normalizedPageSize, filtered.size());
        int to = Math.min(from + normalizedPageSize, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", filtered.subList(from, to));
        result.put("total", filtered.size());
        result.put("pageNum", normalizedPageNum);
        result.put("pageSize", normalizedPageSize);
        result.put("onlineCount", nodeRepository.countByDeletedFalseAndStatus("ONLINE"));
        result.put("offlineCount", nodeRepository.countByDeletedFalseAndStatus("OFFLINE"));
        return result;
    }

    /** 节点详情：基本信息 + 心跳历史 + 最近命令。 */
    public Map<String, Object> nodeDetail(String nodeIdOrCode) {
        AgNode node = nodeRepository.findByNodeCodeAndDeletedFalse(nodeIdOrCode).orElse(null);
        if (node == null) {
            try {
                node = nodeRepository.findByIdAndDeletedFalse(Long.parseLong(nodeIdOrCode)).orElse(null);
            } catch (NumberFormatException ignored) {
                // 非数字 ID，忽略
            }
        }
        if (node == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "前置节点不存在：" + nodeIdOrCode);
        }
        Map<String, Object> detail = nodeVo(node);
        List<Map<String, Object>> heartbeats = new ArrayList<>();
        for (AgHeartbeat heartbeat : heartbeatRepository.findTop20ByNodeCodeAndDeletedFalseOrderByIdDesc(node.getNodeCode())) {
            heartbeats.add(AgentMaps.toMap(heartbeat));
        }
        List<Map<String, Object>> commands = new ArrayList<>();
        for (AgCommand command : commandRepository.findTop10ByNodeCodeAndDeletedFalseOrderByIdDesc(node.getNodeCode())) {
            commands.add(commandPayload(command));
        }
        detail.put("heartbeats", heartbeats);
        detail.put("recentCommands", commands);
        detail.put("pendingCommands", commandRepository.countByNodeCodeAndStatusAndDeletedFalse(node.getNodeCode(), "PENDING"));
        detail.put("heartbeatHistoryCount", heartbeatRepository.countByNodeCodeAndDeletedFalse(node.getNodeCode()));
        detail.put("online", "ONLINE".equalsIgnoreCase(node.getStatus()));
        detail.put("offlineSeconds", node.getLastHeartbeatTime() == null ? null
                : java.time.Duration.between(node.getLastHeartbeatTime(), LocalDateTime.now()).getSeconds());
        return detail;
    }

    /** 命令历史（管理端）。 */
    public List<Map<String, Object>> commandHistory(String nodeId, int limit) {
        List<AgCommand> commands;
        if (nodeId != null && !nodeId.isBlank()) {
            commands = commandRepository.findTop10ByNodeCodeAndDeletedFalseOrderByIdDesc(nodeId.trim());
        } else {
            commands = commandRepository.findByDeletedFalseOrderByIdDesc().stream()
                    .limit(Math.max(1, Math.min(100, limit))).toList();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgCommand command : commands) {
            list.add(commandPayload(command));
        }
        return list;
    }

    /**
     * 定时巡检：心跳超时的在线节点自动置离线（每 60 秒一次）。
     */
    @Scheduled(fixedDelay = 60000L)
    public void sweepOfflineNodes() {
        if (!schedulerEnabled) {
            return;
        }
        try {
            long timeout = Math.max(30L, heartbeatTimeoutSeconds);
            for (AgNode node : nodeRepository.findByDeletedFalseOrderByIdAsc()) {
                if (!"ONLINE".equalsIgnoreCase(node.getStatus()) || node.getLastHeartbeatTime() == null) {
                    continue;
                }
                long offlineSeconds = java.time.Duration.between(node.getLastHeartbeatTime(), LocalDateTime.now()).getSeconds();
                if (offlineSeconds > timeout) {
                    node.setStatus("OFFLINE");
                    nodeRepository.save(node);
                    log.warn("节点 {} 心跳超时 {} 秒，标记离线", node.getNodeCode(), offlineSeconds);
                    saveHeartbeat(node, node.getCpuUsage(), node.getMemUsage(), node.getDiskUsage(),
                            "心跳超时自动判离线", LocalDateTime.now());
                }
            }
        } catch (Exception e) {
            log.warn("节点心跳巡检异常：{}", e.getMessage());
        }
    }

    // ====================== 内部辅助 ======================

    private AgNode requireNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "nodeId 不能为空");
        }
        Optional<AgNode> byCode = nodeRepository.findByNodeCodeAndDeletedFalse(nodeId.trim());
        if (byCode.isPresent()) {
            return byCode.get();
        }
        try {
            return nodeRepository.findByIdAndDeletedFalse(Long.parseLong(nodeId.trim()))
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "前置节点不存在：" + nodeId));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "前置节点不存在：" + nodeId);
        }
    }

    /** 节点令牌校验：令牌传入时严格比对（吊销/轮换后旧令牌失效）；未传时按已登录会话放行（管理端调试）。 */
    private void validateToken(AgNode node, String nodeToken) {
        if (node.getNodeToken() == null || node.getNodeToken().isBlank()) {
            return;
        }
        if (nodeToken == null || nodeToken.isBlank()) {
            return;
        }
        if (!node.getNodeToken().equals(nodeToken.trim())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "节点令牌无效，请重新注册");
        }
    }

    private void saveHeartbeat(AgNode node, Double cpu, Double mem, Double disk, String message, LocalDateTime time) {
        AgHeartbeat heartbeat = new AgHeartbeat();
        heartbeat.setNodeCode(node.getNodeCode());
        heartbeat.setNodeName(node.getNodeName());
        heartbeat.setIp(node.getIp());
        heartbeat.setCpuUsage(cpu);
        heartbeat.setMemUsage(mem);
        heartbeat.setDiskUsage(disk);
        heartbeat.setStatus(node.getStatus());
        heartbeat.setReportTime(time);
        heartbeat.setMessage(message);
        heartbeatRepository.save(heartbeat);
    }

    /** SENT 超过 2 分钟未 ACK 的命令重新入队，避免节点重启后命令丢失。 */
    private void resetStaleCommands(String nodeCode) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(2);
        for (AgCommand command : commandRepository
                .findByNodeCodeAndStatusAndDeletedFalseOrderByPriorityDescIdAsc(nodeCode, "SENT")) {
            if (command.getSendTime() == null || command.getSendTime().isBefore(threshold)) {
                command.setStatus("PENDING");
                command.setRetryCount((command.getRetryCount() == null ? 0 : command.getRetryCount()) + 1);
                commandRepository.save(command);
                log.info("命令 {} 超时未 ACK，重新入队", command.getCommandId());
            }
        }
    }

    private Map<String, Object> nodeVo(AgNode node) {
        Map<String, Object> map = AgentMaps.toMap(node);
        // 对前端统一暴露 nodeId 语义，同时保留 nodeCode 别名
        map.put("nodeId", node.getNodeCode());
        map.put("capabilities", parseJsonList(node.getCapabilitiesJson()));
        map.put("pendingCommands", commandRepository.countByNodeCodeAndStatusAndDeletedFalse(node.getNodeCode(), "PENDING"));
        map.put("heartbeatCount", heartbeatRepository.countByNodeCodeAndDeletedFalse(node.getNodeCode()));
        map.put("online", "ONLINE".equalsIgnoreCase(node.getStatus()));
        if (node.getLastHeartbeatTime() != null) {
            map.put("offlineSeconds", java.time.Duration.between(node.getLastHeartbeatTime(), LocalDateTime.now()).getSeconds());
        }
        return map;
    }

    private Map<String, Object> commandPayload(AgCommand command) {
        Map<String, Object> map = AgentMaps.toMap(command);
        map.put("nodeId", command.getNodeCode());
        try {
            map.put("payload", command.getPayloadJson() == null ? Map.of()
                    : JsonUtil.fromJson(command.getPayloadJson(), Map.class));
        } catch (Exception e) {
            map.put("payload", Map.of());
        }
        return map;
    }

    private List<?> parseJsonList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : JsonUtil.fromJson(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildFingerprint(String nodeCode, String token) {
        String raw = IdGen.sha256(nodeCode + "|" + token + "|share-exchange-mtls-demo");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(32, raw.length()); i += 2) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(raw.substring(i, i + 2).toUpperCase());
        }
        return sb.toString();
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Path uploadDir() {
        return Paths.get(System.getProperty("user.dir", ".")).resolve("data/agent/uploads").normalize();
    }

    private Path importDir() {
        return Paths.get(System.getProperty("user.dir", ".")).resolve("data/agent/import").normalize();
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    /** 分片上传会话。 */
    private static final class UploadSession {
        private final String uploadId;
        private final String fileName;
        private final int totalChunks;
        private final String nodeCode;
        private final Set<Integer> received = ConcurrentHashMap.newKeySet();

        private UploadSession(String uploadId, String fileName, int totalChunks, String nodeCode) {
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.totalChunks = totalChunks;
            this.nodeCode = nodeCode;
        }
    }

    /** 已导入的摆渡包（供管理端查询，正式环境应落库）。 */
    public Map<String, Object> importResult(String packageId) {
        Map<String, Object> record = importResults.get(packageId);
        if (record == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导入记录不存在：" + packageId);
        }
        return record;
    }

    /** 条件反射式保留字段，避免误删能力清单常量。 */
    private static final List<String> DEFAULT_CAPABILITIES = new ArrayList<>(new LinkedHashSet<>(
            List.of("TASK_RUN", "FILE_TRANSFER", "TASK_STOP")));

    /** 默认能力清单（注册未上报时使用）。 */
    public static List<String> defaultCapabilities() {
        return new ArrayList<>(DEFAULT_CAPABILITIES);
    }
}
