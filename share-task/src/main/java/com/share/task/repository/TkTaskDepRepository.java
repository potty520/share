package com.share.task.repository;

import com.share.task.entity.TkTaskDep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 任务依赖（DAG 邻接表）仓储。 */
public interface TkTaskDepRepository extends JpaRepository<TkTaskDep, Long> {

    List<TkTaskDep> findByDeletedFalse();

    List<TkTaskDep> findByTaskIdAndDeletedFalse(Long taskId);

    List<TkTaskDep> findByUpTaskIdAndDeletedFalse(Long upTaskId);

    List<TkTaskDep> findByTaskIdAndUpTaskIdAndDeletedFalse(Long taskId, Long upTaskId);
}
