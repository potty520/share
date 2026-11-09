package com.share.catalog.repository;

import com.share.catalog.entity.CatalogVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 目录版本快照仓储。 */
public interface CatalogVersionRepository extends JpaRepository<CatalogVersion, Long> {

    List<CatalogVersion> findByCatalogIdAndDeletedFalseOrderByVersionNoDescIdDesc(Long catalogId);

    List<CatalogVersion> findByCatalogIdAndVersionNoAndDeletedFalse(Long catalogId, Integer versionNo);

    Optional<CatalogVersion> findByIdAndDeletedFalse(Long id);

    long countByDeletedFalse();
}
