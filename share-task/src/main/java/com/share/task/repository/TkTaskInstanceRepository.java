package com.share.task.repository;

import com.share.task.entity.TkTaskInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 任务实例仓储。 */
public interface TkTaskInstanceRepository extends JpaRepository<TkTaskInstance, Long> {

    Optional<TkTaskInstance> findByIdAndDeletedFalse(Long id);

    List<TkTaskInstance> findByDeletedFalseOrderByIdDesc();

    List<TkTaskInstance> findByTaskIdAndDeletedFalseOrderByIdDesc(Long taskId);

    List<TkTaskInstance> findTop5ByTaskIdAndDeletedFalseOrderByIdDesc(Long taskId);

    long countByTaskIdAndDeletedFalseAndStatus(Long taskId, String status);

    long countByDeletedFalse();

    List<TkTaskInstance> findByStatusAndDeletedFalse(String status);
}
