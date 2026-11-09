package com.share.ops.notify.repository;

import com.share.ops.notify.entity.NotifyMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 站内消息仓储。 */
public interface NotifyMessageRepository extends JpaRepository<NotifyMessage, Long>,
        JpaSpecificationExecutor<NotifyMessage> {

    java.util.Optional<NotifyMessage> findByIdAndDeletedFalse(Long id);

    long countByReceiverIdAndReadFlagFalseAndDeletedFalse(Long receiverId);

    long countByReceiverIdAndDeletedFalse(Long receiverId);

    /** 某用户的未读消息（含广播） */
    @Query("select m from NotifyMessage m where m.deleted = false "
            + "and (m.receiverId = :userId or m.receiverId is null) and m.readFlag = false "
            + "order by m.createTime desc")
    List<NotifyMessage> findUnreadOf(@Param("userId") Long userId);

    /** 批量已读：只影响本人（含广播需按 ID 指定） */
    @Modifying
    @Query("update NotifyMessage m set m.readFlag = true, m.readTime = :now where m.deleted = false "
            + "and m.receiverId = :userId and m.readFlag = false")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 广播消息已读（演示环境：广播已读状态共享） */
    @Modifying
    @Query("update NotifyMessage m set m.readFlag = true, m.readTime = :now where m.deleted = false "
            + "and m.receiverId is null and m.readFlag = false")
    int markBroadcastRead(@Param("now") LocalDateTime now);

    /** 某用户未读数（含广播） */
    @Query("select count(m) from NotifyMessage m where m.deleted = false "
            + "and (m.receiverId = :userId or m.receiverId is null) and m.readFlag = false")
    long countUnreadOf(@Param("userId") Long userId);

    long countByDeletedFalse();

    /** 指定时间之后的消息数（今日消息） */
    long countByDeletedFalseAndCreateTimeAfter(LocalDateTime time);
}
