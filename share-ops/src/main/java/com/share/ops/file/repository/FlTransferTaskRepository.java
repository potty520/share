package com.share.ops.file.repository;

import com.share.ops.file.entity.FlTransferTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 文件收发任务仓储。 */
public interface FlTransferTaskRepository extends JpaRepository<FlTransferTask, Long>,
        JpaSpecificationExecutor<FlTransferTask> {

    Optional<FlTransferTask> findByIdAndDeletedFalse(Long id);

    List<FlTransferTask> findAllByDeletedFalseOrderByCreateTimeDesc();

    List<FlTransferTask> findAllByEnabledTrueAndDeletedFalse();

    long countByDeletedFalse();

    long countByEnabledTrueAndDeletedFalse();

    boolean existsByTaskNameAndDeletedFalse(String taskName);
}
