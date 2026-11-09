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
 * 目录资源主体（FR-03，表 cat_catalog）。
 * <p>状态机：DRAFT → PENDING → PUBLISHED / REJECTED；PUBLISHED → OFFLINE。</p>
 * <p>注意：密级入库列名为 {@code level}（架构文档 7 章与跨域读取约定），接口同时以
 * {@code secretLevel} 与 {@code level} 两个字段名输出，方便各端按需取用。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "cat_catalog", indexes = {
        @Index(name = "idx_cat_catalog_code", columnList = "resource_code"),
        @Index(name = "idx_cat_catalog_status", columnList = "status"),
        @Index(name = "idx_cat_catalog_classify", columnList = "classify_id"),
        @Index(name = "idx_cat_catalog_dept", columnList = "dept_id")
})
public class CatalogResource extends BaseEntity {

    /** 资源编码，全局唯一，如 CAT-ENT-BASE */
    @Column(name = "resource_code", length = 64, nullable = false, unique = true)
    private String resourceCode;

    /** 资源名称 */
    @Column(name = "resource_name", length = 128, nullable = false)
    private String resourceName;

    /** 资源摘要 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** 所属分类 ID */
    @Column(name = "classify_id")
    private Long classifyId;

    /** 提供方部门 ID */
    @Column(name = "dept_id")
    private Long deptId;

    /** 提供方部门名称（冗余） */
    @Column(name = "dept_name", length = 64)
    private String deptName;

    /** 共享属性：UNCONDITIONAL（无条件）/ CONDITIONAL（有条件）/ NO_SHARE（不予共享） */
    @Column(name = "share_type", length = 16)
    private String shareType;

    /** 密级 1~4（等级 4 最高） */
    @Column(name = "level")
    private Integer level;

    /** 状态：DRAFT / PENDING / PUBLISHED / REJECTED / OFFLINE */
    @Column(length = 16)
    private String status;

    /** 挂接的数据源 ID */
    @Column(name = "source_id")
    private Long sourceId;

    /** 挂接数据源类型（冗余，便于列表展示） */
    @Column(name = "source_type", length = 32)
    private String sourceType;

    /** 挂接库表名 */
    @Column(name = "table_name", length = 128)
    private String tableName;

    /** 挂接文件路径（文件类挂接） */
    @Column(name = "file_path", length = 512)
    private String filePath;

    /** 挂接接口地址（API 类挂接） */
    @Column(name = "api_url", length = 512)
    private String apiUrl;

    /** 更新频率，如 每日 / 实时 / 每月 */
    @Column(name = "update_frequency", length = 64)
    private String updateFrequency;

    /** 提供方联系方式 */
    @Column(name = "provider_contact", length = 128)
    private String providerContact;

    /** 标签，逗号分隔 */
    @Column(length = 512)
    private String tags;

    /** 当前版本号（首次创建为 1，发布后修改递增） */
    @Column(name = "version_no")
    private Integer versionNo;

    /** 最近发布时间 */
    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    /** 最近提交审核时间 */
    @Column(name = "submit_time")
    private LocalDateTime submitTime;

    /** 审核意见 */
    @Column(name = "audit_comment", length = 500)
    private String auditComment;

    /** 审核人 */
    @Column(name = "audit_by", length = 64)
    private String auditBy;

    /** 审核时间 */
    @Column(name = "audit_time")
    private LocalDateTime auditTime;

    /** 备注 */
    @Column(columnDefinition = "TEXT")
    private String remark;

    /** 接口别名：secretLevel ↔ level（同一列） */
    public Integer getSecretLevel() {
        return level;
    }

    public void setSecretLevel(Integer secretLevel) {
        this.level = secretLevel;
    }
}
