package com.share.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 认证相关请求体（集中定义，便于契约对齐）。 */
public final class AuthRequests {

    private AuthRequests() {
    }

    /** 登录入参：admin/security 角色必须携带 smsCode。 */
    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password,
                               String smsCode) {
    }

    /** 获取短信验证码入参（演示环境直接返回验证码）。 */
    public record SmsCodeRequest(@NotBlank(message = "用户名不能为空") String username) {
    }

    /** 刷新令牌入参。 */
    public record RefreshRequest(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    /** 修改口令入参。 */
    public record PasswordChangeRequest(@NotBlank(message = "原口令不能为空") String oldPassword,
                                        @NotBlank(message = "新口令不能为空") String newPassword) {
    }

    /** 登出入参：可选携带 refreshToken 一并吊销。 */
    public record LogoutRequest(String refreshToken) {
    }
}
