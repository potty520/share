package com.share.share.repository;

import com.share.share.entity.ApprovalGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 共享授权仓储。 */
public interface ApprovalGrantRepository extends JpaRepository<ApprovalGrant, Long>,
        JpaSpecificationExecutor<ApprovalGrant> {

    Optional<ApprovalGrant> findByIdAndDeletedFalse(Long id);

    Optional<ApprovalGrant> findByApplyIdAndDeletedFalse(Long applyId);

    List<ApprovalGrant> findByStatusAndDeletedFalseOrderByEndTimeAsc(String status);

    long countByStatusAndDeletedFalse(String status);

    List<ApprovalGrant> findByStatusAndEndTimeBeforeAndDeletedFalse(String status, LocalDateTime time);

    List<ApprovalGrant> findByStatusAndEndTimeBetweenAndDeletedFalse(String status, LocalDateTime from, LocalDateTime to);
}
