package com.share.auth.repository;

import com.share.auth.entity.SysDept;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 组织部门仓储。 */
public interface SysDeptRepository extends JpaRepository<SysDept, Long>, JpaSpecificationExecutor<SysDept> {

    List<SysDept> findByDeletedFalseOrderBySortNoAscIdAsc();

    Optional<SysDept> findByIdAndDeletedFalse(Long id);

    Optional<SysDept> findByDeptCodeAndDeletedFalse(String deptCode);

    boolean existsByParentIdAndDeletedFalse(Long parentId);

    boolean existsByDeptCode(String deptCode);

    boolean existsByDeptCodeAndDeletedFalseAndIdNot(String deptCode, Long id);

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(Integer status);
}
