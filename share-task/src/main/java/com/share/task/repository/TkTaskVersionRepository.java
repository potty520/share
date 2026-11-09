package com.share.task.repository;

import com.share.task.entity.TkTaskVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 任务版本仓储。 */
public interface TkTaskVersionRepository extends JpaRepository<TkTaskVersion, Long> {

    List<TkTaskVersion> findByTaskIdAndDeletedFalseOrderByVersionNoDesc(Long taskId);

    long countByTaskIdAndDeletedFalse(Long taskId);
}
