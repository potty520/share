package com.share.auth.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色-权限关联（FR-13）。一行代表某角色被授予的一个权限标识。
 */
@Getter
@Setter
@Entity
@Table(name = "sys_role_permission", indexes = {
        @Index(name = "idx_rp_role", columnList = "role_code")
})
public class SysRolePermission extends BaseEntity {

    @Column(name = "role_code", nullable = false, length = 32)
    private String roleCode;

    @Column(name = "permission_code", nullable = false, length = 64)
    private String permissionCode;
}
