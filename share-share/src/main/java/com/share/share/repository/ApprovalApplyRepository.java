package com.share.share.repository;

import com.share.share.entity.ApprovalApply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 共享申请单仓储。 */
public interface ApprovalApplyRepository extends JpaRepository<ApprovalApply, Long>,
        JpaSpecificationExecutor<ApprovalApply> {

    Optional<ApprovalApply> findByIdAndDeletedFalse(Long id);

    Optional<ApprovalApply> findByApplyNoAndDeletedFalse(String applyNo);

    List<ApprovalApply> findByStatusAndDeletedFalseOrderByIdDesc(String status);

    long countByStatusAndDeletedFalse(String status);

    long countByDeletedFalse();

    List<ApprovalApply> findByApplicantIdAndDeletedFalseOrderByIdDesc(Long applicantId);
}
