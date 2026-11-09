package com.share.security.repository;

import com.share.security.entity.SecClassifyTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 分级标签仓储。 */
public interface SecClassifyTagRepository
        extends JpaRepository<SecClassifyTag, Long>, JpaSpecificationExecutor<SecClassifyTag> {

    Optional<SecClassifyTag> findByIdAndDeletedFalse(Long id);

    List<SecClassifyTag> findByTargetTypeAndTargetIdAndDeletedFalse(String targetType, Long targetId);

    List<SecClassifyTag> findByTargetTypeAndDeletedFalseOrderByIdAsc(String targetType);

    List<SecClassifyTag> findByDeletedFalseOrderByIdAsc();

    long countByDeletedFalse();

    long countByDeletedFalseAndLevel(Integer level);

    long countByDeletedFalseAndTargetType(String targetType);

    /** 按密级分组统计（概览用）。 */
    @Query("select t.level, count(t) from SecClassifyTag t where t.deleted = false group by t.level order by t.level")
    List<Object[]> countGroupByLevel();

    /** 按目标类型分组统计。 */
    @Query("select t.targetType, count(t) from SecClassifyTag t where t.deleted = false group by t.targetType")
    List<Object[]> countGroupByTargetType();

    @Query("select count(t) from SecClassifyTag t where t.deleted = false and t.level = :level and t.targetType = :targetType")
    long countByLevelAndTargetType(@Param("level") Integer level, @Param("targetType") String targetType);
}
