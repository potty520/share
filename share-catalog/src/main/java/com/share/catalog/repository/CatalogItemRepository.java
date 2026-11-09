package com.share.catalog.repository;

import com.share.catalog.entity.CatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 目录数据项仓储。 */
public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    List<CatalogItem> findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(Long catalogId);

    long countByCatalogIdAndDeletedFalse(Long catalogId);

    long countByCatalogIdAndSensitiveAndDeletedFalse(Long catalogId, Boolean sensitive);

    long countByDeletedFalse();

    /**
     * 批量统计（列表页避免 N+1）：返回 [catalogId, 数据项总数, 敏感项数]。
     */
    @Query("select i.catalogId, count(i), sum(case when i.sensitive = true then 1 else 0 end) "
            + "from CatalogItem i where i.catalogId in :catalogIds and i.deleted = false group by i.catalogId")
    List<Object[]> countGroupByCatalogIds(@Param("catalogIds") List<Long> catalogIds);

    /** 覆盖式保存数据项：先物理清除旧项再写入新项。 */
    @Modifying
    @Query("delete from CatalogItem i where i.catalogId = :catalogId")
    int deleteByCatalogId(@Param("catalogId") Long catalogId);

    @Modifying
    @Query("update CatalogItem i set i.deleted = true where i.catalogId = :catalogId")
    int logicDeleteByCatalogId(@Param("catalogId") Long catalogId);
}
