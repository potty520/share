package com.share.common.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 当前登录用户（从 JWT 解析，放入 UserContext）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long userId;
    private String username;
    private String realName;
    private String roleCode;
    private Long deptId;
    private String deptName;
    /** token 类型：access / refresh */
    private String tokenType;
    /** 双因素标记 */
    private boolean mfaPassed;

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(roleCode);
    }
}
