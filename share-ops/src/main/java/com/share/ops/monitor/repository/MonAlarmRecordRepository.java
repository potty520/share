package com.share.ops.monitor.repository;

import com.share.ops.monitor.entity.MonAlarmRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 告警记录仓储。 */
public interface MonAlarmRecordRepository extends JpaRepository<MonAlarmRecord, Long>,
        JpaSpecificationExecutor<MonAlarmRecord> {

    Optional<MonAlarmRecord> findByIdAndDeletedFalse(Long id);

    long countByStatusAndDeletedFalse(String status);

    long countByAlarmLevelAndStatusAndDeletedFalse(String alarmLevel, String status);

    /** 同规则同对象未确认的告警（用于持续告警合并） */
    List<MonAlarmRecord> findByRuleIdAndObjectCodeAndStatusAndDeletedFalseOrderByFirstTimeDesc(
            Long ruleId, String objectCode, String status);

    /** 最近告警（总览/大屏） */
    List<MonAlarmRecord> findTop10ByDeletedFalseOrderByFirstTimeDesc();

    List<MonAlarmRecord> findTop50ByDeletedFalseOrderByFirstTimeDesc();

    @Query("select a.alarmLevel, count(a) from MonAlarmRecord a where a.deleted = false "
            + "and a.firstTime >= :from group by a.alarmLevel")
    List<Object[]> countByLevelSince(@Param("from") LocalDateTime from);

    @Query("select a.status, count(a) from MonAlarmRecord a where a.deleted = false group by a.status")
    List<Object[]> countByStatus();

    /** 区间内告警（服务层按天聚合趋势） */
    List<MonAlarmRecord> findByDeletedFalseAndFirstTimeAfterOrderByFirstTimeAsc(LocalDateTime from);

    /** 规则命中次数 Top（运维报表用） */
    @Query("select a.ruleName, count(a) from MonAlarmRecord a where a.deleted = false "
            + "and a.firstTime >= :from group by a.ruleName order by count(a) desc")
    List<Object[]> topRules(@Param("from") LocalDateTime from);
}
