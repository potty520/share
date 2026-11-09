package com.share.task.repository;

import com.share.task.entity.TkQualityIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 质量问题工单仓储。 */
public interface TkQualityIssueRepository extends JpaRepository<TkQualityIssue, Long> {

    Optional<TkQualityIssue> findByIdAndDeletedFalse(Long id);

    List<TkQualityIssue> findByDeletedFalseOrderByIdDesc();

    List<TkQualityIssue> findByReportIdAndDeletedFalse(Long reportId);

    List<TkQualityIssue> findByStatusAndDeletedFalse(String status);

    long countByDeletedFalse();
}
