package com.share.ops.audit.repository;

import com.share.ops.audit.entity.AudOperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 审计日志仓储（统计聚合在服务层完成，便于跨库兼容）。 */
public interface AudOperationLogRepository extends JpaRepository<AudOperationLog, Long>,
        JpaSpecificationExecutor<AudOperationLog> {

    Optional<AudOperationLog> findByIdAndDeletedFalse(Long id);

    long countByDeletedFalse();

    long countByResultAndDeletedFalse(String result);

    /** 模块维度统计 */
    @Query("select l.module, count(l) from AudOperationLog l where l.deleted = false group by l.module order by count(l) desc")
    List<Object[]> countByModule();

    /** 动作维度统计 */
    @Query("select l.action, count(l) from AudOperationLog l where l.deleted = false group by l.action order by count(l) desc")
    List<Object[]> countByAction();

    /** 结果维度统计 */
    @Query("select l.result, count(l) from AudOperationLog l where l.deleted = false group by l.result")
    List<Object[]> countByResult();

    /** 操作人维度统计（Top 操作者） */
    @Query("select l.operatorName, count(l) from AudOperationLog l where l.deleted = false "
            + "and l.operatorName is not null group by l.operatorName order by count(l) desc")
    List<Object[]> countByOperator();

    /** 时间升序拉取区间日志（服务层内存分析：非工作时间、高频、连续失败） */
    List<AudOperationLog> findByDeletedFalseAndOperateTimeAfterOrderByOperateTimeAsc(LocalDateTime from);

    List<AudOperationLog> findByDeletedFalseAndOperateTimeBetweenOrderByOperateTimeAsc(
            LocalDateTime from, LocalDateTime to);

    /** 连续失败分析用：按时间倒序取最近失败/成功记录 */
    List<AudOperationLog> findTop200ByDeletedFalseOrderByOperateTimeDesc();

    /** 保留期清理：取出超过截止时间的未删除日志 */
    List<AudOperationLog> findByDeletedFalseAndOperateTimeBefore(LocalDateTime cutoff);

    /** 数据流转追踪：按目标对象 ID 或明细关键词检索 */
    @Query("select l from AudOperationLog l where l.deleted = false and "
            + "(l.targetId = :key or l.detail like concat('%', :key, '%')) order by l.operateTime asc")
    List<AudOperationLog> findForTrace(@Param("key") String key);
}
