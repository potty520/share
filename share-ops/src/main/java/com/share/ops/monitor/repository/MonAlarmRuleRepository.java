package com.share.ops.monitor.repository;

import com.share.ops.monitor.entity.MonAlarmRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 告警规则仓储。 */
public interface MonAlarmRuleRepository extends JpaRepository<MonAlarmRule, Long>,
        JpaSpecificationExecutor<MonAlarmRule> {

    Optional<MonAlarmRule> findByIdAndDeletedFalse(Long id);

    List<MonAlarmRule> findAllByDeletedFalseOrderByCreateTimeDesc();

    List<MonAlarmRule> findAllByEnabledTrueAndDeletedFalse();

    long countByDeletedFalse();

    long countByEnabledTrueAndDeletedFalse();
}
