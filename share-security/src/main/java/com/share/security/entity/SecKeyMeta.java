package com.share.security.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 密钥元数据（FR-10）：仅保存别名、算法、状态、版本与轮换时间，不保存明文密钥。
 * <p>真实密钥由 KMS/配置文件托管，数据库只做生命周期管理。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "sec_key_meta")
public class SecKeyMeta extends BaseEntity {

    @Column(name = "key_alias", nullable = false, length = 100)
    private String keyAlias;

    /** 算法，如 AES-256-GCM / SM4-CBC */
    @Column(name = "algorithm", nullable = false, length = 32)
    private String algorithm;

    /** ACTIVE 启用 / ROTATED 已轮换 / DISABLED 停用 */
    @Column(name = "key_status", length = 16)
    private String keyStatus = "ACTIVE";

    @Column(name = "key_version")
    private Integer keyVersion = 1;

    /** 密钥指纹（摘要），用于比对不使用明文 */
    @Column(name = "key_fingerprint", length = 64)
    private String keyFingerprint;

    @Column(name = "last_rotated_time")
    private LocalDateTime lastRotatedTime;

    @Column(name = "next_rotate_time")
    private LocalDateTime nextRotateTime;

    /** 轮换周期（天） */
    @Column(name = "rotate_days")
    private Integer rotateDays = 90;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
