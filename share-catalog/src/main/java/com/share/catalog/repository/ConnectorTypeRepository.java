package com.share.catalog.repository;

import com.share.catalog.entity.ConnectorType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 连接器类型字典仓储。 */
public interface ConnectorTypeRepository extends JpaRepository<ConnectorType, Long> {

    List<ConnectorType> findByDeletedFalseOrderBySortNoAsc();

    Optional<ConnectorType> findByTypeCodeAndDeletedFalse(String typeCode);

    long countByDeletedFalse();
}
