package com.share.auth.repository;

import com.share.auth.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 角色仓储。 */
public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    List<SysRole> findByDeletedFalseOrderBySortNoAscIdAsc();

    Optional<SysRole> findByIdAndDeletedFalse(Long id);

    Optional<SysRole> findByRoleCodeAndDeletedFalse(String roleCode);

    boolean existsByRoleCode(String roleCode);

    boolean existsByRoleCodeAndIdNot(String roleCode, Long id);

    long countByDeletedFalse();
}
