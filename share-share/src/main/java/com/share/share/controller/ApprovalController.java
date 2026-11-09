package com.share.share.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.share.dto.ApprovalActionRequest;
import com.share.share.dto.ApprovalSubmitRequest;
import com.share.share.dto.GrantRevokeRequest;
import com.share.share.entity.ApprovalApply;
import com.share.share.service.ApprovalService;
import com.share.share.service.GrantService;
import com.share.share.support.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * 共享申请审批与授权接口（FR-08，/api/approval/v1）。
 * <p>申请提交 → 多级审批（业务管理员 → 数据提供方 → 安全管理员[涉敏]）→ 驳回/撤回/转办/续期
 * → 授权查询与回收 → 审批流配置。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/approval/v1")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final GrantService grantService;

    /** 提交共享申请（按数据密级自动生成审批节点）。 */
    @PostMapping("/applies")
    @RequirePermission(PermissionConstants.APPROVAL_APPLY)
    @AuditLog(action = "APPROVAL_SUBMIT", targetType = "APPROVAL")
    public Result<Map<String, Object>> submit(@Valid @RequestBody ApprovalSubmitRequest req) {
        return Result.ok(approvalService.submit(req));
    }

    /** 申请列表：status / mine（我的申请）/ todo（我的待办：当前节点角色匹配）/ keyword。 */
    @GetMapping("/applies")
    public Result<List<ApprovalApply>> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) Boolean mine,
                                            @RequestParam(required = false) Boolean todo,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        PageResult<ApprovalApply> page = approvalService.list(status, mine, todo, keyword, pageNum, pageSize);
        return Result.ok(page.list(), page.total(), page.pageNum(), page.pageSize());
    }

    /** 申请详情（含审批节点轨迹、授权、凭据与可执行动作标记）。 */
    @GetMapping("/applies/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(approvalService.detail(id));
    }

    /** 审批通过（推进当前节点；末级通过自动签发凭据与授权）。 */
    @PostMapping("/applies/{id}/approve")
    @RequirePermission(PermissionConstants.APPROVAL_APPROVE)
    @AuditLog(action = "APPROVAL_APPROVE", targetType = "APPROVAL")
    public Result<Map<String, Object>> approve(@PathVariable Long id,
                                               @RequestBody(required = false) ApprovalActionRequest req) {
        return Result.ok(approvalService.approve(id, req));
    }

    /** 驳回（必须填写审批意见）。 */
    @PostMapping("/applies/{id}/reject")
    @RequirePermission(PermissionConstants.APPROVAL_APPROVE)
    @AuditLog(action = "APPROVAL_REJECT", targetType = "APPROVAL")
    public Result<Map<String, Object>> reject(@PathVariable Long id,
                                              @RequestBody(required = false) ApprovalActionRequest req) {
        return Result.ok(approvalService.reject(id, req));
    }

    /** 申请人撤回。 */
    @PostMapping("/applies/{id}/withdraw")
    @RequirePermission(PermissionConstants.APPROVAL_APPLY)
    @AuditLog(action = "APPROVAL_WITHDRAW", targetType = "APPROVAL")
    public Result<Map<String, Object>> withdraw(@PathVariable Long id) {
        return Result.ok(approvalService.withdraw(id));
    }

    /** 转办当前节点给其他用户。 */
    @PostMapping("/applies/{id}/transfer")
    @RequirePermission(PermissionConstants.APPROVAL_APPROVE)
    @AuditLog(action = "APPROVAL_TRANSFER", targetType = "APPROVAL")
    public Result<Map<String, Object>> transfer(@PathVariable Long id,
                                                @RequestBody(required = false) ApprovalActionRequest req) {
        return Result.ok(approvalService.transfer(id, req));
    }

    /** 续期（已通过申请延长授权与凭据有效期）。 */
    @PostMapping("/applies/{id}/renew")
    @RequirePermission(PermissionConstants.APPROVAL_APPLY)
    @AuditLog(action = "APPROVAL_RENEW", targetType = "APPROVAL")
    public Result<Map<String, Object>> renew(@PathVariable Long id,
                                             @RequestBody(required = false) ApprovalActionRequest req) {
        return Result.ok(approvalService.renew(id, req));
    }

    /** 授权列表（含 ACTIVE/EXPIRED/REVOKED 状态、到期时间、剩余天数）。 */
    @GetMapping("/grants")
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<List<Map<String, Object>>> grants(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long consumerId,
                                                    @RequestParam(required = false) Long apiId,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Boolean expiring,
                                                    @RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "10") int pageSize) {
        PageResult<Map<String, Object>> page = grantService.list(status, consumerId, apiId, keyword,
                expiring, pageNum, pageSize);
        return Result.ok(page.list(), page.total(), page.pageNum(), page.pageSize());
    }

    /** 回收授权（联动吊销凭据）。 */
    @PostMapping("/grants/{id}/revoke")
    @RequirePermission(PermissionConstants.APPROVAL_APPROVE)
    @AuditLog(action = "GRANT_REVOKE", targetType = "GRANT")
    public Result<Map<String, Object>> revokeGrant(@PathVariable Long id,
                                                   @RequestBody(required = false) GrantRevokeRequest req) {
        return Result.ok(grantService.revoke(id, req == null ? null : req.reason()));
    }

    /** 手工触发到期扫描（演示/运维用；定时任务每日 02:00 自动执行）。 */
    @PostMapping("/grants/scan")
    @RequirePermission(PermissionConstants.APPROVAL_APPROVE)
    @AuditLog(action = "GRANT_SCAN", targetType = "GRANT")
    public Result<Map<String, Object>> scanGrants() {
        return Result.ok(grantService.scanExpiringGrants());
    }

    /** 审批流节点配置（普通流 2 级 / 涉敏流 3 级）。 */
    @GetMapping("/flow-config")
    public Result<Map<String, Object>> flowConfig() {
        return Result.ok(approvalService.flowConfig());
    }
}
