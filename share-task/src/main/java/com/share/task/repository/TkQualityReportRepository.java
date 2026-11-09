package com.share.task.repository;

import com.share.task.entity.TkQualityReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 质量检核报告仓储。 */
public interface TkQualityReportRepository extends JpaRepository<TkQualityReport, Long> {

    Optional<TkQualityReport> findByIdAndDeletedFalse(Long id);

    List<TkQualityReport> findByDeletedFalseOrderByIdDesc();

    List<TkQualityReport> findByRuleIdAndDeletedFalseOrderByIdDesc(Long ruleId);

    long countByDeletedFalse();
}
