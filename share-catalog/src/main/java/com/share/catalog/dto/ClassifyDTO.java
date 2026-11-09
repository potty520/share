package com.share.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 分类节点新增/修改入参。 */
@Getter
@Setter
public class ClassifyDTO {

    /** 父节点 ID，根节点为空 */
    private Long parentId;

    @NotBlank(message = "分类名称不能为空")
    private String name;

    /** 维度：SUBJECT / DEPT / DOMAIN（修改时可空，沿用原值） */
    private String dimension;

    /** 分类编码，可空则自动生成 */
    private String code;

    private Integer sortNo;

    private String description;
}
