package com.share.ops.notify.repository;

import com.share.ops.notify.entity.NotifyFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** 资源使用反馈仓储。 */
public interface NotifyFeedbackRepository extends JpaRepository<NotifyFeedback, Long>,
        JpaSpecificationExecutor<NotifyFeedback> {

    java.util.Optional<NotifyFeedback> findByIdAndDeletedFalse(Long id);

    long countByStatusAndDeletedFalse(String status);

    long countByDeletedFalse();

    /** 按资源维度统计平均分（提供方质量视图） */
    @Query("select f.resourceType, f.resourceId, count(f), avg(f.rating) from NotifyFeedback f "
            + "where f.deleted = false group by f.resourceType, f.resourceId order by avg(f.rating) desc")
    List<Object[]> groupByResource();
}
