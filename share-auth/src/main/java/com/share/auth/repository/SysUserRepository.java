package com.share.auth.repository;

import com.share.auth.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 用户仓储。 */
public interface SysUserRepository extends JpaRepository<SysUser, Long>, JpaSpecificationExecutor<SysUser> {

    Optional<SysUser> findByUsernameAndDeletedFalse(String username);

    Optional<SysUser> findByIdAndDeletedFalse(Long id);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, Long id);

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(Integer status);

    long countByDeletedFalseAndMfaEnabledTrue();

    long countByDeletedFalseAndDeptId(Long deptId);

    long countByDeletedFalseAndRoleCode(String roleCode);

    List<SysUser> findTop10ByDeletedFalseOrderByLastLoginTimeDesc();
}
