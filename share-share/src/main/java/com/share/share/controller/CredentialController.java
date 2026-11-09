package com.share.share.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.share.dto.CredentialCreateRequest;
import com.share.share.dto.GrantRevokeRequest;
import com.share.share.entity.ShareCredential;
import com.share.share.service.ShareCredentialService;
import com.share.share.support.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 共享调用凭据接口（FR-07，/api/share/v1/credentials）。
 * <p>签发时 AppSecret 只返回一次，之后仅能查看前缀；吊销后立即失效。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/share/v1/credentials")
public class CredentialController {

    private final ShareCredentialService credentialService;

    /** 凭据分页列表（appKey/apiCode/consumerName 模糊检索）。 */
    @GetMapping
    @RequirePermission(PermissionConstants.SHARE_READ)
    public Result<List<ShareCredential>> list(@RequestParam(required = false) Long apiId,
                                              @RequestParam(required = false) Long consumerId,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") int pageNum,
                                              @RequestParam(defaultValue = "10") int pageSize) {
        PageResult<ShareCredential> page = credentialService.list(apiId, consumerId, status, keyword,
                pageNum, pageSize);
        return Result.ok(page.list(), page.total(), page.pageNum(), page.pageSize());
    }

    /** 签发凭据：返回 appKey 与只出现一次的 appSecret。 */
    @PostMapping
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "CREDENTIAL_ISSUE", targetType = "CREDENTIAL", recordArgs = false)
    public Result<Map<String, Object>> issue(@Valid @RequestBody CredentialCreateRequest req) {
        return Result.ok(credentialService.issue(req));
    }

    /** 吊销凭据。 */
    @PutMapping("/{id}/revoke")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "CREDENTIAL_REVOKE", targetType = "CREDENTIAL")
    public Result<Map<String, Object>> revoke(@PathVariable Long id,
                                              @RequestBody(required = false) GrantRevokeRequest req) {
        return Result.ok(credentialService.revoke(id, req == null ? null : req.reason()));
    }

    /** 轮换密钥：返回新 AppSecret（仅一次），旧密钥在宽限期内继续可用。 */
    @PostMapping("/{id}/rotate")
    @RequirePermission(PermissionConstants.SHARE_WRITE)
    @AuditLog(action = "CREDENTIAL_ROTATE", targetType = "CREDENTIAL", recordArgs = false)
    public Result<Map<String, Object>> rotate(@PathVariable Long id,
                                              @RequestParam(required = false) Integer graceHours) {
        return Result.ok(credentialService.rotate(id, graceHours));
    }
}
