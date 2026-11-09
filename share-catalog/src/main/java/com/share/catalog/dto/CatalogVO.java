package com.share.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录资源响应对象。
 * <p>列表场景不含 items/versions；详情场景装配 items（数据项）、versions（历史版本）与 attach（挂接信息）。</p>
 */
@Getter
@Setter
public class CatalogVO {

    private Long id;
    private String resourceCode;
    private String resourceName;
    private String summary;
    private Long classifyId;
    private String classifyName;
    private Long deptId;
    private String deptName;
    private String shareType;
    private String shareTypeText;
    /** 密级 1~4 */
    private Integer secretLevel;
    /** 兼容字段：与 secretLevel 同义（架构文档中的 cat_catalog.level） */
    private Integer level;
    private String secretLevelText;
    private String status;
    private String statusText;
    private Long sourceId;
    private String sourceType;
    private String sourceTypeName;
    private String tableName;
    private String filePath;
    private String apiUrl;
    private String updateFrequency;
    private String providerContact;
    /** 标签，逗号分隔 */
    private String tags;
    /** 标签数组 */
    private List<String> tagList = new ArrayList<>();
    private Integer versionNo;
    private LocalDateTime publishTime;
    private LocalDateTime submitTime;
    private String auditComment;
    private String auditBy;
    private LocalDateTime auditTime;
    private String remark;
    /** 数据项数量 */
    private long itemCount;
    /** 敏感数据项数量 */
    private long sensitiveItemCount;
    /** 是否涉敏（密级≥3 或存在敏感字段） */
    private boolean sensitive;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String createBy;
    private String updateBy;
    /** 数据项（仅详情） */
    private List<CatalogItemVO> items;
    /** 历史版本（仅详情） */
    private List<CatalogVersionVO> versions;
    /** 挂接信息（仅详情） */
    private Attach attach;

    /** 挂接信息：目录与物理数据（库表/文件/接口）的绑定关系。 */
    @Getter
    @Setter
    public static class Attach {
        private Long sourceId;
        private String sourceName;
        private String sourceType;
        private String sourceTypeName;
        /** TABLE / FILE / API / NONE */
        private String mode;
        private String tableName;
        private String filePath;
        private String apiUrl;
        /** 数据源是否可用（启用） */
        private boolean sourceEnabled;
        /** 样例预览可用 */
        private boolean previewable;
    }
}
