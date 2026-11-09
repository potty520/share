package com.share.catalog.repository;

import com.share.catalog.entity.CatalogResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 目录资源仓储。 */
public interface CatalogResourceRepository extends JpaRepository<CatalogResource, Long>,
        JpaSpecificationExecutor<CatalogResource> {

    Optional<CatalogResource> findByIdAndDeletedFalse(Long id);

    Optional<CatalogResource> findByResourceCodeAndDeletedFalse(String resourceCode);

    List<CatalogResource> findByDeletedFalseOrderByCreateTimeAsc();

    long countByDeletedFalse();

    long countByStatusAndDeletedFalse(String status);

    long countByClassifyIdAndStatusAndDeletedFalse(Long classifyId, String status);

    long countByClassifyIdAndDeletedFalse(Long classifyId);

    long countByLevelGreaterThanEqualAndDeletedFalse(Integer level);

    /** 涉敏目录数：密级 ≥ 3，或存在任一敏感数据项。 */
    @Query("select count(r) from CatalogResource r where r.deleted = false and (r.level >= 3 or r.id in "
            + "(select distinct i.catalogId from CatalogItem i where i.sensitive = true and i.deleted = false))")
    long countSensitiveCatalog();

    @Query("select r.deptId, count(r) from CatalogResource r where r.deleted = false group by r.deptId")
    List<Object[]> countGroupByDept();

    @Query("select r.shareType, count(r) from CatalogResource r where r.deleted = false group by r.shareType")
    List<Object[]> countGroupByShareType();

    @Query("select r.level, count(r) from CatalogResource r where r.deleted = false group by r.level")
    List<Object[]> countGroupByLevel();

    @Query("select r.status, count(r) from CatalogResource r where r.deleted = false group by r.status")
    List<Object[]> countGroupByStatus();

    @Query("select distinct i.catalogId from CatalogItem i where i.sensitive = true and i.deleted = false")
    List<Long> findSensitiveCatalogIds();
}
