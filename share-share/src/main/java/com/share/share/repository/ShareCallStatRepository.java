package com.share.share.repository;

import com.share.share.entity.ShareCallStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

/** 共享服务调用计量仓储。 */
public interface ShareCallStatRepository extends JpaRepository<ShareCallStat, Long>,
        JpaSpecificationExecutor<ShareCallStat> {

    List<ShareCallStat> findByCallTimeBetween(LocalDateTime from, LocalDateTime to);

    List<ShareCallStat> findByApiIdAndCallTimeBetween(Long apiId, LocalDateTime from, LocalDateTime to);

    long countByApiId(Long apiId);
}
