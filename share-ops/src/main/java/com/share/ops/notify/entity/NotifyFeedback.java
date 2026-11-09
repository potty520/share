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
 * 资源使用反馈（FR-14）：使用方对数据质量的评价，可回复整改结果。
 */
@Getter
@Setter
@Entity
@Table(name = "notify_feedback", indexes = {
        @Index(name = "idx_notify_fb_resource", columnList = "resource_id"),
        @Index(name = "idx_notify_fb_user", columnList = "user_id")
})
public class NotifyFeedback extends BaseEntity {

    /** 资源类型：CATALOG / TASK / FILE / API */
    @Column(name = "resource_type", length = 32)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "resource_name", length = 300)
    private String resourceName;

    /** 评分 1~5 */
    @Column(name = "rating")
    private Integer rating;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name", length = 64)
    private String userName;

    @Column(name = "dept_id")
    private Long deptId;

    /** 提供方/管理员回复 */
    @Column(name = "reply", columnDefinition = "TEXT")
    private String reply;

    @Column(name = "reply_by", length = 64)
    private String replyBy;

    @Column(name = "reply_time")
    private LocalDateTime replyTime;

    /** OPEN（待处理）/ REPLIED（已回复）/ CLOSED（已关闭） */
    @Column(name = "status", length = 16)
    private String status;
}
