package com.share.catalog.dto;

import lombok.Getter;
import lombok.Setter;

/** 目录列表查询参数。 */
@Getter
@Setter
public class CatalogQuery extends com.share.common.api.PageQuery {

    /** 关键词：编码/名称/摘要/标签 */
    private String keyword;

    /** 分类 ID（含子分类） */
    private Long classifyId;

    /** 提供方部门 */
    private Long deptId;

    /** 共享属性 */
    private String shareType;

    /** 状态；不传且 all!=true 时默认只查 PUBLISHED */
    private String status;

    /** 标签（模糊匹配） */
    private String tag;

    /** 是否查询全部状态（编目管理用） */
    private Boolean all;

    /** 密级过滤 */
    private Integer secretLevel;

    /** 是否只看涉敏目录 */
    private Boolean sensitive;
}
