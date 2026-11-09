package com.share.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 认证相关响应体，字段与《实现契约》2.1 节保持一致。 */
public final class LoginResponse {

    private LoginResponse() {
    }

    /** 登录用户简要信息。 */
    public record UserInfo(Long id,
                           String username,
                           String realName,
                           String roleCode,
                           String roleName,
                           Long deptId,
                           String deptName,
                           String phone,
                           String email,
                           Integer status,
                           Boolean mfaEnabled,
                           LocalDateTime lastLoginTime) {
    }

    /** 登录 / 刷新令牌结果：access 15 分钟、refresh 7 天。 */
    public record TokenResult(String accessToken,
                              String refreshToken,
                              String tokenType,
                              long expiresIn,
                              UserInfo user,
                              List<String> permissions) {
    }

    /** 个人中心：用户 + 权限 + 前端菜单。 */
    public record Profile(UserInfo user,
                          List<String> permissions,
                          List<Menu> menus,
                          List<String> roles) {
    }

    /** 前端菜单节点（由权限过滤）。 */
    public record Menu(String key,
                       String path,
                       String name,
                       String title,
                       String icon,
                       String permission,
                       List<Menu> children) {
    }

    /** 短信验证码结果（演示环境返回 code 便于联调）。 */
    public record SmsCodeResult(String username,
                                String code,
                                int expiresIn,
                                String tip) {
    }
}
