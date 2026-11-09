package com.share.auth.controller;

import com.share.auth.dto.AuthRequests;
import com.share.auth.dto.LoginResponse;
import com.share.auth.service.AuthService;
import com.share.common.api.Result;
import com.share.common.audit.AuditAspect;
import com.share.common.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（FR-13）：登录 / 短信验证码 / 刷新 / 登出 / 个人中心 / 修改口令。
 * <p>login、sms-code、refresh 在 share-common 白名单内，无需令牌。</p>
 */
@RestController
@RequestMapping("/api/auth/v1")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录：ADMIN/SECURITY 角色需短信验证码（先调 /sms-code 获取）。 */
    @PostMapping("/login")
    @AuditLog(action = "AUTH_LOGIN", targetType = "USER", recordArgs = false)
    public Result<LoginResponse.TokenResult> login(@Valid @RequestBody AuthRequests.LoginRequest request,
                                                   HttpServletRequest httpRequest) {
        String clientIp = AuditAspect.clientIp(httpRequest);
        return Result.ok(authService.login(request, clientIp));
    }

    /** 生成短信验证码：演示环境将验证码直接放在 data.code 返回。 */
    @PostMapping("/sms-code")
    @AuditLog(action = "AUTH_SMS_CODE", targetType = "USER", recordArgs = false)
    public Result<LoginResponse.SmsCodeResult> smsCode(@Valid @RequestBody AuthRequests.SmsCodeRequest request) {
        return Result.ok(authService.sendSmsCode(request.username()));
    }

    /** 刷新令牌：返回新的 access/refresh 令牌（旧 refresh 立即失效）。 */
    @PostMapping("/refresh")
    public Result<LoginResponse.TokenResult> refresh(@Valid @RequestBody AuthRequests.RefreshRequest request) {
        return Result.ok(authService.refresh(request.refreshToken()));
    }

    /** 登出：access / refresh 令牌加入内存黑名单。 */
    @PostMapping("/logout")
    @AuditLog(action = "AUTH_LOGOUT", targetType = "USER", recordArgs = false)
    public Result<Void> logout(@RequestBody(required = false) AuthRequests.LogoutRequest request,
                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        String accessToken = extractToken(authorization);
        authService.logout(accessToken, request == null ? null : request.refreshToken());
        return Result.ok();
    }

    /** 个人中心：当前用户 + 权限 + 前端菜单。 */
    @GetMapping("/profile")
    public Result<LoginResponse.Profile> profile() {
        return Result.ok(authService.profile());
    }

    /** 修改当前用户口令。 */
    @PostMapping("/password")
    @AuditLog(action = "AUTH_PASSWORD_CHANGE", targetType = "USER", recordArgs = false)
    public Result<Void> changePassword(@Valid @RequestBody AuthRequests.PasswordChangeRequest request) {
        authService.changePassword(request.oldPassword(), request.newPassword());
        return Result.ok();
    }

    private String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }
}
