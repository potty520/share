package com.share.task.repository;

import com.share.task.entity.TkQualityRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 数据质量规则仓储。 */
public interface TkQualityRuleRepository extends JpaRepository<TkQualityRule, Long> {

    Optional<TkQualityRule> findByIdAndDeletedFalse(Long id);

    List<TkQualityRule> findByDeletedFalseOrderByIdAsc();

    boolean existsByRuleCodeAndDeletedFalse(String ruleCode);

    long countByDeletedFalse();
}
