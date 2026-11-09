package com.share.catalog.repository;

import com.share.catalog.entity.Classify;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 目录分类树仓储。 */
public interface ClassifyRepository extends JpaRepository<Classify, Long> {

    List<Classify> findByDeletedFalseOrderBySortNoAscIdAsc();

    List<Classify> findByDimensionAndDeletedFalseOrderBySortNoAscIdAsc(String dimension);

    List<Classify> findByParentIdAndDeletedFalse(Long parentId);

    long countByDeletedFalse();
}
