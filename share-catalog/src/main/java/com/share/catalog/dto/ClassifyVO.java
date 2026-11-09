package com.share.catalog.dto;

import com.share.catalog.entity.Classify;
import com.share.catalog.support.Dicts;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 分类树节点响应对象（children 递归）。 */
@Getter
@Setter
public class ClassifyVO {

    private Long id;
    private Long parentId;
    private String name;
    private String dimension;
    private String dimensionText;
    private String code;
    private Integer sortNo;
    private String description;
    /** 直接挂接的目录资源数 */
    private long resourceCount;
    /** 子节点 */
    private List<ClassifyVO> children = new ArrayList<>();

    public static ClassifyVO from(Classify e) {
        ClassifyVO vo = new ClassifyVO();
        vo.id = e.getId();
        vo.parentId = e.getParentId();
        vo.name = e.getName();
        vo.dimension = e.getDimension();
        vo.dimensionText = Dicts.dimensionText(e.getDimension());
        vo.code = e.getCode();
        vo.sortNo = e.getSortNo();
        vo.description = e.getDescription();
        return vo;
    }
}
