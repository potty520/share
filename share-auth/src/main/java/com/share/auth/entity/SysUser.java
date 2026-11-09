package com.share.auth.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台用户（FR-13）。口令采用 SHA-256(盐 + 口令) 自实现加盐存储，不引入额外依赖。
 */
@Getter
@Setter
@Entity
@Table(name = "sys_user")
public class SysUser extends BaseEntity {

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** 口令摘要（十六进制），不对外暴露 */
    @JsonIgnore
    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    /** 口令盐（十六进制），不对外暴露 */
    @JsonIgnore
    @Column(name = "password_salt", length = 64)
    private String passwordSalt;

    @Column(name = "real_name", length = 64)
    private String realName;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email", length = 128)
    private String email;

    @Column(name = "dept_id")
    private Long deptId;

    /** 角色编码，取值见 SeedConstants.ROLE_* */
    @Column(name = "role_code", length = 32)
    private String roleCode;

    /** 1 启用 / 0 停用 */
    @Column(name = "status")
    private Integer status = 1;

    /** 是否强制双因素认证 */
    @Column(name = "mfa_enabled")
    private Boolean mfaEnabled = false;

    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;

    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp;

    @Column(name = "login_fail_count")
    private Integer loginFailCount = 0;

    @Column(name = "remark", length = 500)
    private String remark;
}
