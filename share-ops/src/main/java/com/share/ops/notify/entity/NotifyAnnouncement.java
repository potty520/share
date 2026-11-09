package com.share.ops.notify.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台公告（FR-14）。
 */
@Getter
@Setter
@Entity
@Table(name = "notify_announcement", indexes = {
        @Index(name = "idx_notify_ann_publish", columnList = "publish_time")
})
public class NotifyAnnouncement extends BaseEntity {

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    /** 公告正文（长文本） */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "publisher_id")
    private Long publisherId;

    @Column(name = "publisher_name", length = 64)
    private String publisherName;

    /** 公告级别：NORMAL / IMPORTANT / URGENT */
    @Column(name = "ann_level", length = 16)
    private String annLevel;

    /** DRAFT / PUBLISHED / OFFLINE */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "top_flag")
    private Boolean topFlag = false;

    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    @Column(name = "read_count")
    private Integer readCount;
}
