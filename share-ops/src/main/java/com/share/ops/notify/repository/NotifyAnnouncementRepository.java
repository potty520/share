package com.share.ops.notify.repository;

import com.share.ops.notify.entity.NotifyAnnouncement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** 公告仓储。 */
public interface NotifyAnnouncementRepository extends JpaRepository<NotifyAnnouncement, Long>,
        JpaSpecificationExecutor<NotifyAnnouncement> {

    Optional<NotifyAnnouncement> findByIdAndDeletedFalse(Long id);

    List<NotifyAnnouncement> findTop10ByStatusAndDeletedFalseOrderByTopFlagDescPublishTimeDesc(String status);

    long countByStatusAndDeletedFalse(String status);

    long countByDeletedFalse();
}
