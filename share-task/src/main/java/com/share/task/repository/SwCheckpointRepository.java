package com.share.task.repository;

import com.share.task.entity.SwCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 交换断点仓储。 */
public interface SwCheckpointRepository extends JpaRepository<SwCheckpoint, Long> {

    List<SwCheckpoint> findByBatchIdAndDeletedFalseOrderByChunkNoAsc(String batchId);

    Optional<SwCheckpoint> findFirstByTaskIdAndDeletedFalseOrderByIdDesc(Long taskId);
}
