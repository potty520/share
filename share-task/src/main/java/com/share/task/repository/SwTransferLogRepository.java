package com.share.task.repository;

import com.share.task.entity.SwTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 数据流转日志仓储。 */
public interface SwTransferLogRepository extends JpaRepository<SwTransferLog, Long> {

    List<SwTransferLog> findByBatchIdAndDeletedFalseOrderByIdAsc(String batchId);

    List<SwTransferLog> findByInstanceIdAndDeletedFalseOrderByIdAsc(Long instanceId);

    List<SwTransferLog> findByTaskIdAndDeletedFalseOrderByIdDesc(Long taskId);
}
