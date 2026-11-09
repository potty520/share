package com.share.catalog.dto;

import com.share.catalog.entity.CatalogItem;
import lombok.Getter;
import lombok.Setter;

/** 目录数据项响应对象。 */
@Getter
@Setter
public class CatalogItemVO {

    private Long id;
    private Long catalogId;
    private String fieldName;
    private String fieldType;
    private String fieldComment;
    private String sampleValue;
    private Boolean sensitive;
    private String maskRuleCode;
    private Integer sortNo;

    public static CatalogItemVO from(CatalogItem e) {
        CatalogItemVO vo = new CatalogItemVO();
        vo.id = e.getId();
        vo.catalogId = e.getCatalogId();
        vo.fieldName = e.getFieldName();
        vo.fieldType = e.getFieldType();
        vo.fieldComment = e.getFieldComment();
        vo.sampleValue = e.getSampleValue();
        vo.sensitive = e.getSensitive() != null && e.getSensitive();
        vo.maskRuleCode = e.getMaskRuleCode();
        vo.sortNo = e.getSortNo();
        return vo;
    }
}
