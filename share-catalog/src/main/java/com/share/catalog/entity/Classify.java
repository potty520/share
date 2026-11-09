package com.share.catalog.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 目录分类树节点（FR-03）。
 * <p>多维度分类：SUBJECT（主题）/ DEPT（部门）/ DOMAIN（业务领域），同一维度内以 parentId 组树。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "cat_classify", indexes = {
        @Index(name = "idx_cat_classify_parent", columnList = "parent_id"),
        @Index(name = "idx_cat_classify_dim", columnList = "dimension")
})
public class Classify extends BaseEntity {

    /** 父节点 ID，根节点为空 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 分类名称 */
    @Column(length = 64, nullable = false)
    private String name;

    /** 维度：SUBJECT / DEPT / DOMAIN */
    @Column(length = 16, nullable = false)
    private String dimension;

    /** 分类编码，维度内唯一 */
    @Column(length = 64)
    private String code;

    /** 排序号 */
    @Column(name = "sort_no")
    private Integer sortNo;

    /** 说明 */
    @Column(length = 500)
    private String description;
}
