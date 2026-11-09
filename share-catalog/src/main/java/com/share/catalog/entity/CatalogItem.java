package com.share.catalog.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 目录数据项（FR-03，表 cat_catalog_item）。
 * <p>一条目录资源包含多个数据项，随资源一起提交；{@code sensitive=true} 的字段在样例预览与
 * 共享调用时按 {@code maskRuleCode} 指定的脱敏规则处理。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "cat_catalog_item", indexes = {
        @Index(name = "idx_cat_item_catalog", columnList = "catalog_id")
})
public class CatalogItem extends BaseEntity {

    /** 所属目录资源 ID */
    @Column(name = "catalog_id", nullable = false)
    private Long catalogId;

    /** 字段名（与挂接表列名一致时可直接用于预览） */
    @Column(name = "field_name", length = 128, nullable = false)
    private String fieldName;

    /** 字段类型，如 VARCHAR(64) / BIGINT / DATE */
    @Column(name = "field_type", length = 64)
    private String fieldType;

    /** 字段说明 */
    @Column(name = "field_comment", length = 500)
    private String fieldComment;

    /** 样例值（编目示例数据） */
    @Column(name = "sample_value", length = 256)
    private String sampleValue;

    /** 是否敏感字段 */
    @Column(name = "is_sensitive")
    private Boolean sensitive;

    /** 脱敏规则编码，如 MASK_PHONE / MASK_ID_CARD / REPLACE_NAME / GENERALIZE_ADDR */
    @Column(name = "mask_rule_code", length = 64)
    private String maskRuleCode;

    /** 排序号 */
    @Column(name = "sort_no")
    private Integer sortNo;
}
