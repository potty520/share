package com.share.share.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 共享授权（FR-08，表 {@code apr_grant}）。
 * <p>审批全部通过后自动生成，绑定凭据与到期时间；到期自动置 EXPIRED 并吊销凭据，支持回收与续期。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "apr_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_apr_grant_no", columnNames = "grant_no"),
        indexes = {
                @Index(name = "idx_apr_grant_status", columnList = "status"),
                @Index(name = "idx_apr_grant_consumer", columnList = "consumer_id")
        })
public class ApprovalGrant extends BaseEntity {

    /** 授权编号（IdGen.credentialNo） */
    @Column(name = "grant_no", nullable = false, length = 64)
    private String grantNo;

    @Column(name = "apply_id")
    private Long applyId;

    @Column(name = "apply_no", length = 64)
    private String applyNo;

    @Column(name = "api_id")
    private Long apiId;

    @Column(name = "api_code", length = 64)
    private String apiCode;

    @Column(name = "catalog_id")
    private Long catalogId;

    @Column(name = "catalog_name", length = 128)
    private String catalogName;

    /** 被授权方 */
    @Column(name = "consumer_id")
    private Long consumerId;

    @Column(name = "consumer_name", length = 64)
    private String consumerName;

    @Column(name = "consumer_dept_id")
    private Long consumerDeptId;

    /** 授权密级 */
    @Column(name = "data_level")
    private Integer level = 1;

    /** 生效时间 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 到期时间 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 状态：ACTIVE / EXPIRED / REVOKED */
    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    /** 关联凭据 */
    @Column(name = "credential_id")
    private Long credentialId;

    /** 吊销时间 */
    @Column(name = "revoke_time")
    private LocalDateTime revokeTime;

    /** 吊销原因 */
    @Column(name = "revoke_reason", length = 255)
    private String revokeReason;

    /** 到期提醒时间（T-7 提醒去重） */
    @Column(name = "remind_time")
    private LocalDateTime remindTime;

    /** QPS 限额（继承共享 API） */
    @Column(name = "qps_limit")
    private Integer qpsLimit = 100;
}
