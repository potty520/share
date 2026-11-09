package com.share.auth.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 组织机构（FR-13）：树形部门表 sys_dept。
 * <p>parent_id=0 表示根部门；部门用于数据级权限与目录归属。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "sys_dept")
public class SysDept extends BaseEntity {

    @Column(name = "dept_name", nullable = false, length = 100)
    private String deptName;

    @Column(name = "dept_code", nullable = false, length = 64)
    private String deptCode;

    /** 上级部门 id，0 表示顶级 */
    @Column(name = "parent_id")
    private Long parentId = 0L;

    @Column(name = "sort_no")
    private Integer sortNo = 0;

    @Column(name = "leader", length = 64)
    private String leader;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email", length = 128)
    private String email;

    /** 1 启用 / 0 停用 */
    @Column(name = "status")
    private Integer status = 1;

    @Column(name = "description", length = 500)
    private String description;
}
