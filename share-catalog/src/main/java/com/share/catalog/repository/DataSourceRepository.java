package com.share.catalog.repository;

import com.share.catalog.entity.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 数据源仓储。 */
public interface DataSourceRepository extends JpaRepository<DataSource, Long>, JpaSpecificationExecutor<DataSource> {

    Optional<DataSource> findByIdAndDeletedFalse(Long id);

    Optional<DataSource> findBySourceCodeAndDeletedFalse(String sourceCode);

    List<DataSource> findByDeletedFalseOrderByCreateTimeAsc();

    long countByDeletedFalse();
}
