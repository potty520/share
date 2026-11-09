package com.share.ops.notify.repository;

import com.share.ops.notify.entity.NotifyChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 通知通道仓储。 */
public interface NotifyChannelRepository extends JpaRepository<NotifyChannel, Long> {

    Optional<NotifyChannel> findByIdAndDeletedFalse(Long id);

    List<NotifyChannel> findAllByDeletedFalseOrderByCreateTimeDesc();

    List<NotifyChannel> findByChannelTypeAndEnabledTrueAndDeletedFalse(String channelType);

    boolean existsByChannelCodeAndDeletedFalse(String channelCode);

    long countByEnabledTrueAndDeletedFalse();

    long countByDeletedFalse();
}
