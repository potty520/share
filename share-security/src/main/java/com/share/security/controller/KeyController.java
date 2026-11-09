package com.share.security.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.security.dto.SecurityRequests;
import com.share.security.entity.SecKeyMeta;
import com.share.security.service.KeyMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 密钥元数据接口（FR-10）：只读列表、登记与轮换，任何接口都不返回明文密钥。
 */
@RestController
@RequestMapping("/api/security/v1/keys")
@RequiredArgsConstructor
public class KeyController {

    private final KeyMetaService keyMetaService;

    /** 密钥元数据列表。 */
    @GetMapping
    @RequirePermission(PermissionConstants.SECURITY_READ)
    public Result<List<SecKeyMeta>> list() {
        return Result.ok(keyMetaService.list());
    }

    /** 登记密钥元数据。 */
    @PostMapping
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_KEY_CREATE", targetType = "KEY_META")
    public Result<SecKeyMeta> create(@RequestBody SecurityRequests.KeyCreateRequest request) {
        return Result.ok(keyMetaService.create(request));
    }

    /** 轮换密钥（版本 +1，更新指纹与下次轮换时间）。 */
    @PostMapping("/{id}/rotate")
    @RequirePermission(PermissionConstants.SECURITY_WRITE)
    @AuditLog(action = "SECURITY_KEY_ROTATE", targetType = "KEY_META")
    public Result<SecKeyMeta> rotate(@PathVariable Long id) {
        return Result.ok(keyMetaService.rotate(id));
    }
}
