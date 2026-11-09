package com.share.task.repository;

import com.share.task.entity.TkTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 交换任务仓储。 */
public interface TkTaskRepository extends JpaRepository<TkTask, Long> {

    Optional<TkTask> findByIdAndDeletedFalse(Long id);

    Optional<TkTask> findByTaskCodeAndDeletedFalse(String taskCode);

    boolean existsByTaskCodeAndDeletedFalse(String taskCode);

    long countByDeletedFalse();

    List<TkTask> findByDeletedFalseOrderByIdDesc();

    List<TkTask> findByDeletedFalseAndEnabledTrueOrderByIdAsc();

    List<TkTask> findByTaskTypeAndDeletedFalse(String taskType);
}
