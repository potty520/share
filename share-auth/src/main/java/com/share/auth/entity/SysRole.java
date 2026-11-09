package com.share.auth.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色（FR-13 RBAC）。内置角色见 SeedConstants.ROLE_*，角色与权限通过 sys_role_permission 关联。
 */
@Getter
@Setter
@Entity
@Table(name = "sys_role")
public class SysRole extends BaseEntity {

    @Column(name = "role_code", nullable = false, length = 32)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 64)
    private String roleName;

    @Column(name = "description", length = 500)
    private String description;

    /** 1 启用 / 0 停用 */
    @Column(name = "status")
    private Integer status = 1;

    @Column(name = "sort_no")
    private Integer sortNo = 0;

    /** 内置角色不允许删除 */
    @Column(name = "builtin")
    private Boolean builtin = false;
}
