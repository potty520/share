package com.share.catalog.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 目录版本快照（FR-03：目录变更与版本管理，表 cat_catalog_version）。
 * <p>每次发布 / 重大变更 / 下架都落一份 JSON 快照，支持历史追溯与版本对比。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "cat_catalog_version", indexes = {
        @Index(name = "idx_cat_version_catalog", columnList = "catalog_id")
})
public class CatalogVersion extends BaseEntity {

    /** 所属目录资源 ID */
    @Column(name = "catalog_id", nullable = false)
    private Long catalogId;

    /** 版本号 */
    @Column(name = "version_no")
    private Integer versionNo;

    /** 变更类型：CREATE / UPDATE / PUBLISH / AUDIT_REJECT / OFFLINE */
    @Column(name = "change_type", length = 32)
    private String changeType;

    /** 变更说明 */
    @Column(name = "change_log", columnDefinition = "TEXT")
    private String changeLog;

    /** 版本快照 JSON（资源主体 + 数据项 + 挂接信息） */
    @Column(columnDefinition = "TEXT")
    private String snapshot;

    /** 该版本的发布时间 */
    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    /** 版本操作人 */
    @Column(length = 64)
    private String operator;
}
