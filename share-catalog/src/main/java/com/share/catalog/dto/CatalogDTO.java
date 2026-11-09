package com.share.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 目录资源新增/修改入参（数据项 items 随资源一起提交）。 */
@Getter
@Setter
public class CatalogDTO {

    /** 资源编码，可空则自动生成 */
    private String resourceCode;

    /** 资源名称 */
    private String resourceName;

    private String summary;

    private Long classifyId;

    private Long deptId;

    private String deptName;

    /** 共享属性：UNCONDITIONAL / CONDITIONAL / NO_SHARE */
    private String shareType;

    /** 密级 1~4 */
    private Integer secretLevel;

    /** 兼容字段：与 secretLevel 同义 */
    private Integer level;

    private Long sourceId;

    private String sourceType;

    private String tableName;

    private String filePath;

    private String apiUrl;

    private String updateFrequency;

    private String providerContact;

    /** 标签：字符串（逗号分隔）或字符串数组 */
    private Object tags;

    private String remark;

    /** 数据项列表 */
    private List<CatalogItemDTO> items;
}
