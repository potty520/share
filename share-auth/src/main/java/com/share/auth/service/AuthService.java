package com.share.auth.service;

import com.share.auth.dto.AuthRequests;
import com.share.auth.dto.LoginResponse;
import com.share.auth.entity.SysDept;
import com.share.auth.entity.SysRole;
import com.share.auth.entity.SysUser;
import com.share.auth.repository.SysDeptRepository;
import com.share.auth.repository.SysRoleRepository;
import com.share.auth.repository.SysUserRepository;
import com.share.auth.support.PasswordUtil;
import com.share.common.api.ErrorCode;
import com.share.common.constant.SeedConstants;
import com.share.common.exception.BizException;
import com.share.common.security.JwtTokenUtil;
import com.share.common.security.LoginUser;
import com.share.common.security.UserContext;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 认证服务（FR-13）：登录（含双因素）、刷新、登出、个人中心、修改口令。
 * <p>access 15 分钟 / refresh 7 天，JWT 由 share-common 的 {@link JwtTokenUtil} 签发。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysDeptRepository deptRepository;
    private final JwtTokenUtil jwtTokenUtil;
    private final SmsCodeService smsCodeService;
    private final TokenBlacklistService blacklistService;
    private final RbacPermissionProvider permissionProvider;
    private final MenuService menuService;

    /** 新口令最小长度（等保要求），取自平台配置 */
    @Value("${share.security.password-min-length:8}")
    private int passwordMinLength;

    /** refresh token 兜底吊销时长：7 天 */
    private static final long REFRESH_TTL_MILLIS = 7L * 24 * 3600 * 1000;

    /**
     * 登录：校验口令 → 双因素（ADMIN/SECURITY 角色或开启 MFA 的账号需短信码）→ 签发令牌。
     */
    @Transactional
    public LoginResponse.TokenResult login(AuthRequests.LoginRequest request, String clientIp) {
        String username = request.username() == null ? "" : request.username().trim();
        SysUser user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new BizException(ErrorCode.AUTH_BAD_CREDENTIALS));
        if (Integer.valueOf(0).equals(user.getStatus())) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (!PasswordUtil.matches(request.password(), user.getPasswordSalt(), user.getPasswordHash())) {
            user.setLoginFailCount((user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1);
            userRepository.save(user);
            throw new BizException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }
        if (requiresMfa(user)) {
            String smsCode = request.smsCode();
            if (smsCode == null || smsCode.isBlank()) {
                throw new BizException(ErrorCode.AUTH_MFA_REQUIRED);
            }
            if (!smsCodeService.verify(user.getUsername(), smsCode)) {
                throw new BizException(ErrorCode.AUTH_MFA_CODE_INVALID);
            }
        }
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        user.setLoginFailCount(0);
        userRepository.save(user);
        log.info("用户登录成功: username={}, role={}", user.getUsername(), user.getRoleCode());
        return buildTokens(user, true);
    }

    /** 生成短信验证码（演示环境直接返回验证码）。 */
    public LoginResponse.SmsCodeResult sendSmsCode(String username) {
        SysUser user = userRepository.findByUsernameAndDeletedFalse(username == null ? "" : username.trim())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (Integer.valueOf(0).equals(user.getStatus())) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        String code = smsCodeService.generate(user.getUsername());
        return new LoginResponse.SmsCodeResult(user.getUsername(), code, smsCodeService.expiresInSeconds(),
                "演示环境验证码直接返回，有效期 5 分钟");
    }

    /**
     * 刷新令牌：校验 refresh token → 轮换新旧令牌（旧 refresh 立即加入黑名单）。
     */
    @Transactional
    public LoginResponse.TokenResult refresh(String refreshToken) {
        LoginUser parsed;
        try {
            parsed = jwtTokenUtil.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }
        if (!"refresh".equals(parsed.getTokenType())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请使用 refreshToken 刷新");
        }
        if (blacklistService.isRevoked(refreshToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌已登出");
        }
        SysUser user = userRepository.findByUsernameAndDeletedFalse(parsed.getUsername())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户不存在"));
        if (Integer.valueOf(0).equals(user.getStatus())) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        // 令牌轮换：旧 refresh token 立即失效
        blacklistService.revoke(refreshToken, REFRESH_TTL_MILLIS);
        return buildTokens(user, true);
    }

    /** 登出：access / refresh 均加入内存黑名单。 */
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            blacklistService.revoke(accessToken, jwtTokenUtil.getAccessMinutes() * 60 * 1000L);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            blacklistService.revoke(refreshToken, REFRESH_TTL_MILLIS);
        }
    }

    /** 当前用户信息 + 权限 + 菜单。 */
    public LoginResponse.Profile profile() {
        LoginUser current = UserContext.get();
        if (current == null || current.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        SysUser user = userRepository.findByIdAndDeletedFalse(current.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户不存在"));
        Set<String> permissions = permissionProvider.permissionsOf(user.getRoleCode());
        boolean admin = SeedConstants.ROLE_ADMIN.equals(normalizeRole(user.getRoleCode()));
        return new LoginResponse.Profile(userInfo(user), List.copyOf(permissions),
                menuService.menusFor(permissions, admin), List.of(normalizeRole(user.getRoleCode())));
    }

    /** 修改当前用户口令。 */
    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        SysUser user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户不存在"));
        if (!PasswordUtil.matches(oldPassword, user.getPasswordSalt(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_BAD_CREDENTIALS, "原口令不正确");
        }
        validateNewPassword(newPassword);
        if (PasswordUtil.matches(newPassword, user.getPasswordSalt(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新口令不能与原口令相同");
        }
        PasswordUtil.applyTo(user, newPassword);
        userRepository.save(user);
        log.info("用户修改口令: username={}", user.getUsername());
    }

    /** 校验新口令强度（长度限制，满足基础等保要求）。 */
    public void validateNewPassword(String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新口令不能为空");
        }
        int min = Math.max(6, passwordMinLength);
        if (newPassword.length() < min) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新口令长度不能小于 " + min + " 位");
        }
        if (newPassword.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新口令长度不能大于 64 位");
        }
    }

    /** 是否需要双因素认证：ADMIN / SECURITY 角色或显式开启 MFA 的账号。 */
    public boolean requiresMfa(SysUser user) {
        String role = normalizeRole(user.getRoleCode());
        return SeedConstants.ROLE_ADMIN.equals(role)
                || SeedConstants.ROLE_SECURITY.equals(role)
                || Boolean.TRUE.equals(user.getMfaEnabled());
    }

    /** 签发 access + refresh 令牌并组装返回体。 */
    private LoginResponse.TokenResult buildTokens(SysUser user, boolean mfaPassed) {
        LoginUser loginUser = LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .roleCode(normalizeRole(user.getRoleCode()))
                .deptId(user.getDeptId())
                .mfaPassed(mfaPassed)
                .tokenType("access")
                .build();
        String accessToken = jwtTokenUtil.createAccessToken(loginUser);
        String refreshToken = jwtTokenUtil.createRefreshToken(loginUser);
        Set<String> permissions = permissionProvider.permissionsOf(loginUser.getRoleCode());
        return new LoginResponse.TokenResult(accessToken, refreshToken, "Bearer",
                jwtTokenUtil.getAccessMinutes() * 60, userInfo(user), List.copyOf(permissions));
    }

    /** 用户信息视图（含部门名、角色名）。 */
    public LoginResponse.UserInfo userInfo(SysUser user) {
        String deptName = user.getDeptId() == null ? null
                : deptRepository.findByIdAndDeletedFalse(user.getDeptId()).map(SysDept::getDeptName).orElse(null);
        String roleName = roleRepository.findByRoleCodeAndDeletedFalse(normalizeRole(user.getRoleCode()))
                .map(SysRole::getRoleName).orElse(normalizeRole(user.getRoleCode()));
        return new LoginResponse.UserInfo(user.getId(), user.getUsername(), user.getRealName(),
                normalizeRole(user.getRoleCode()), roleName, user.getDeptId(), deptName,
                user.getPhone(), user.getEmail(), user.getStatus(), user.getMfaEnabled(), user.getLastLoginTime());
    }

    /** 角色编码统一大写，避免大小写导致的鉴权失败。 */
    public static String normalizeRole(String roleCode) {
        return roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT);
    }
}
