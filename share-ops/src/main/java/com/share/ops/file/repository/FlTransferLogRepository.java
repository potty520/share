package com.share.ops.file.repository;

import com.share.ops.file.entity.FlTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/** 文件传输日志仓储。 */
public interface FlTransferLogRepository extends JpaRepository<FlTransferLog, Long>,
        JpaSpecificationExecutor<FlTransferLog> {

    long countByStatusAndDeletedFalse(String status);

    /** 区间内某结果的传输次数（运维报表） */
    long countByStatusAndDeletedFalseAndStartTimeAfter(String status, LocalDateTime from);

    /** 区间内成功传输字节数 */
    @Query("select coalesce(sum(l.fileSize), 0) from FlTransferLog l "
            + "where l.deleted = false and l.status = 'SUCCESS' and l.startTime >= :from and l.startTime < :to")
    Long sumSuccessBytes(LocalDateTime from, LocalDateTime to);

    List<FlTransferLog> findTop20ByDeletedFalseOrderByStartTimeDesc();

    List<FlTransferLog> findTop50ByTaskIdAndDeletedFalseOrderByStartTimeDesc(Long taskId);
}
