package com.share.task.repository;

import com.share.task.entity.SwBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 交换批次仓储：batchId 为幂等判重键。 */
public interface SwBatchRepository extends JpaRepository<SwBatch, Long> {

    Optional<SwBatch> findByBatchIdAndDeletedFalse(String batchId);

    List<SwBatch> findByDeletedFalseOrderByIdDesc();

    List<SwBatch> findByTaskIdAndDeletedFalseOrderByIdDesc(Long taskId);

    List<SwBatch> findTop20ByDeletedFalseOrderByIdDesc();

    long countByDeletedFalse();
}
