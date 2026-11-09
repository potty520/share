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
 * 共享调用凭据（FR-07，表 {@code shr_credential}）。
 * <p>AppSecret 只在校验时使用摘要比对，库中仅存 SHA-256 摘要与前缀，明文仅在签发响应中返回一次。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "shr_credential",
        uniqueConstraints = @UniqueConstraint(name = "uk_shr_cred_app_key", columnNames = "app_key"),
        indexes = {
                @Index(name = "idx_shr_cred_api", columnList = "api_id"),
                @Index(name = "idx_shr_cred_status", columnList = "status")
        })
public class ShareCredential extends BaseEntity {

    /** 绑定的共享 API */
    @Column(name = "api_id", nullable = false)
    private Long apiId;

    @Column(name = "api_code", length = 64)
    private String apiCode;

    @Column(name = "api_name", length = 128)
    private String apiName;

    /** 使用方用户 ID */
    @Column(name = "consumer_id")
    private Long consumerId;

    /** 使用方名称 */
    @Column(name = "consumer_name", length = 128)
    private String consumerName;

    /** AppKey（请求头 X-App-Key） */
    @Column(name = "app_key", nullable = false, length = 64)
    private String appKey;

    /** AppSecret 摘要（SHA-256，不存明文；序列化时永不下发） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "app_secret_hash", nullable = false, length = 128)
    private String appSecretHash;

    /** AppSecret 展示前缀（便于识别，不可用于调用） */
    @Column(name = "app_secret_preview", length = 32)
    private String appSecretPreview;

    /** 授权密级（动态脱敏判定依据：低于数据密级则强制脱敏） */
    @Column(name = "grant_level")
    private Integer grantLevel = 1;

    /** QPS 限额（来自共享 API 或申请单覆盖） */
    @Column(name = "qps_limit")
    private Integer qpsLimit = 100;

    /** 状态：ACTIVE / REVOKED / EXPIRED */
    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    /** 到期时间 */
    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    /** 吊销时间 */
    @Column(name = "revoke_time")
    private LocalDateTime revokeTime;

    /** 吊销原因 */
    @Column(name = "revoke_reason", length = 255)
    private String revokeReason;

    /** 最近调用时间 */
    @Column(name = "last_call_time")
    private LocalDateTime lastCallTime;

    /** 累计调用次数 */
    @Column(name = "call_count")
    private Long callCount = 0L;

    /** 关联授权 ID（由审批通过签发的凭据） */
    @Column(name = "grant_id")
    private Long grantId;

    /** 关联申请单 ID */
    @Column(name = "apply_id")
    private Long applyId;

    /** 备注 */
    @Column(name = "remark", length = 255)
    private String remark;

    /** IP 白名单（逗号分隔，支持 CIDR；为空表示不限制来源 IP） */
    @Column(name = "ip_whitelist", columnDefinition = "TEXT")
    private String ipWhitelist;

    /** IP 黑名单（逗号分隔，支持 CIDR；优先级高于白名单） */
    @Column(name = "ip_blacklist", columnDefinition = "TEXT")
    private String ipBlacklist;

    /** 轮换前旧密钥摘要（宽限期内仍可用于调用） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "prev_app_secret_hash", length = 128)
    private String prevAppSecretHash;

    /** 轮换前旧密钥展示前缀 */
    @Column(name = "prev_app_secret_preview", length = 32)
    private String prevAppSecretPreview;

    /** 旧密钥失效时间（轮换宽限期截止） */
    @Column(name = "prev_secret_expire_time")
    private LocalDateTime prevSecretExpireTime;

    /** 最近一次密钥轮换时间 */
    @Column(name = "secret_rotated_at")
    private LocalDateTime secretRotatedAt;
}
