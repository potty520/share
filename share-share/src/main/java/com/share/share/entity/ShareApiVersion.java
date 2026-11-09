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
 * 共享 API 版本快照（FR-07）。
 * <p>每次上线发布或手工冻结版本时，将 API 定义与参数配置整体序列化保存；
 * 支持查看历史版本与一键回滚，回滚后生成新的版本号，保证版本单调递增。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "shr_api_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_shr_api_version_no",
                columnNames = {"api_id", "version_no"}),
        indexes = @Index(name = "idx_shr_api_version_api", columnList = "api_id"))
public class ShareApiVersion extends BaseEntity {

    @Column(name = "api_id", nullable = false)
    private Long apiId;

    @Column(name = "api_code", length = 64)
    private String apiCode;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "version_label", length = 20)
    private String versionLabel;

    /** 快照时的 API 状态（ONLINE / OFFLINE / DRAFT） */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "editor", length = 64)
    private String editor;

    /** 快照内容：API 字段 + 参数定义 */
    @Column(name = "snapshot_json", columnDefinition = "LONGTEXT")
    private String snapshotJson;

    @Column(name = "published_time")
    private LocalDateTime publishedTime;
}
