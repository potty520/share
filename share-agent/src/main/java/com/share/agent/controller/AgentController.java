package com.share.agent.controller;

import com.share.agent.dto.AgentAckRequest;
import com.share.agent.dto.AgentChunkRequest;
import com.share.agent.dto.AgentDispatchRequest;
import com.share.agent.dto.AgentHeartbeatRequest;
import com.share.agent.dto.AgentPackageRequest;
import com.share.agent.dto.AgentRegisterRequest;
import com.share.agent.service.AgentService;
import com.share.common.api.PageQuery;
import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 前置节点 Agent 接口（FR-02）：注册（白名单）/心跳/命令长轮询/结果回传/分片上传/摆渡导入/节点管理。
 */
@RestController
@RequestMapping("/api/agent/v1")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /** 安装凭证注册（无需 JWT，AuthFilter 已白名单）。 */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody AgentRegisterRequest request) {
        return Result.ok(agentService.register(request));
    }

    /** 心跳上报（30 秒一次）。 */
    @PostMapping("/heartbeat")
    public Result<Map<String, Object>> heartbeat(@RequestBody AgentHeartbeatRequest request) {
        return Result.ok(agentService.heartbeat(request));
    }

    /** 命令长轮询：无命令时最多等待 timeout 秒（上限 20）。 */
    @GetMapping("/commands")
    public Result<Map<String, Object>> commands(@RequestParam String nodeId,
                                                @RequestParam(required = false) String nodeToken,
                                                @RequestParam(defaultValue = "20") int timeout) {
        return Result.ok(agentService.pollCommands(nodeId, nodeToken, timeout));
    }

    /** 命令执行结果回传。 */
    @PostMapping("/commands/{id}/ack")
    public Result<Map<String, Object>> ack(@PathVariable String id, @RequestBody AgentAckRequest request) {
        return Result.ok(agentService.ack(id, request));
    }

    /** 管理端下发命令。 */
    @PostMapping("/commands")
    @RequirePermission(PermissionConstants.AGENT_WRITE)
    @AuditLog(action = "AGENT_COMMAND_DISPATCH", targetType = "AGENT_COMMAND")
    public Result<Map<String, Object>> dispatch(@RequestBody AgentDispatchRequest request) {
        return Result.ok(agentService.dispatch(request, UserContext.userId(), UserContext.username()));
    }

    /** 命令历史（管理端）。 */
    @GetMapping("/commands/history")
    @RequirePermission(PermissionConstants.AGENT_READ)
    public Result<List<Map<String, Object>>> commandHistory(@RequestParam(required = false) String nodeId,
                                                            @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(agentService.commandHistory(nodeId, limit));
    }

    /** 分片上传。 */
    @PostMapping("/upload/chunk")
    public Result<Map<String, Object>> uploadChunk(@RequestBody AgentChunkRequest request) {
        return Result.ok(agentService.uploadChunk(request));
    }

    /** 离线摆渡包导入（签名 + 清单校验）。 */
    @PostMapping("/package/import")
    public Result<Map<String, Object>> importPackage(@RequestBody AgentPackageRequest request) {
        return Result.ok(agentService.importPackage(request));
    }

    /** 摆渡包导入记录查询。 */
    @GetMapping("/packages/{id}")
    @RequirePermission(PermissionConstants.AGENT_READ)
    public Result<Map<String, Object>> importResult(@PathVariable String id) {
        return Result.ok(agentService.importResult(id));
    }

    /** 节点列表（管理端）。 */
    @GetMapping("/nodes")
    @RequirePermission(PermissionConstants.AGENT_READ)
    public Result<List<Map<String, Object>>> nodes(@Validated PageQuery pageQuery,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status) {
        Map<String, Object> page = agentService.pageNodes(pageQuery.getPageNum(), pageQuery.getPageSize(), keyword, status);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.get("list");
        long total = page.get("total") instanceof Number number ? number.longValue() : list.size();
        return Result.ok(list, total, pageQuery.getPageNum(), pageQuery.getPageSize());
    }

    /** 节点详情（含心跳历史与最近命令）。 */
    @GetMapping("/nodes/{id}")
    @RequirePermission(PermissionConstants.AGENT_READ)
    public Result<Map<String, Object>> nodeDetail(@PathVariable String id) {
        return Result.ok(agentService.nodeDetail(id));
    }
}
