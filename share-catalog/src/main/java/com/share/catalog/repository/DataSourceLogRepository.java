package com.share.catalog.repository;

import com.share.catalog.entity.DataSourceLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 数据源变更历史仓储。 */
public interface DataSourceLogRepository extends JpaRepository<DataSourceLog, Long> {

    Page<DataSourceLog> findBySourceIdAndDeletedFalseOrderByCreateTimeDesc(Long sourceId, Pageable pageable);

    List<DataSourceLog> findTop20BySourceIdAndDeletedFalseOrderByCreateTimeDesc(Long sourceId);

    long countBySourceIdAndDeletedFalse(Long sourceId);
}
