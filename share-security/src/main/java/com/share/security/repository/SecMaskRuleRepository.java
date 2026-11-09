package com.share.security.repository;

import com.share.security.entity.SecMaskRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 脱敏规则仓储。 */
public interface SecMaskRuleRepository
        extends JpaRepository<SecMaskRule, Long>, JpaSpecificationExecutor<SecMaskRule> {

    List<SecMaskRule> findByDeletedFalseOrderByIdAsc();

    List<SecMaskRule> findByDeletedFalseAndEnabledTrueOrderByIdAsc();

    Optional<SecMaskRule> findByIdAndDeletedFalse(Long id);

    Optional<SecMaskRule> findByRuleCodeAndDeletedFalse(String ruleCode);

    boolean existsByRuleCode(String ruleCode);

    boolean existsByRuleCodeAndIdNot(String ruleCode, Long id);

    long countByDeletedFalse();

    long countByDeletedFalseAndEnabledTrue();
}
