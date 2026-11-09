package com.share.auth.repository;

import com.share.auth.entity.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 角色-权限关联仓储。 */
public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, Long> {

    List<SysRolePermission> findByRoleCode(String roleCode);

    List<SysRolePermission> findByRoleCodeIn(Collection<String> roleCodes);

    long countByRoleCode(String roleCode);

    @Modifying
    @Query("delete from SysRolePermission rp where rp.roleCode = :roleCode")
    void deleteByRoleCode(@Param("roleCode") String roleCode);

    @Modifying
    @Query("delete from SysRolePermission rp where rp.roleCode in :roleCodes")
    void deleteByRoleCodeIn(@Param("roleCodes") Collection<String> roleCodes);
}
