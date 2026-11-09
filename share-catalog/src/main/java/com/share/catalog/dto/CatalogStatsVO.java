package com.share.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 目录统计（工作台 / 统计卡片 / 按部门分布图）。 */
@Getter
@Setter
public class CatalogStatsVO {

    /** 目录总数（未删除） */
    private long total;
    /** 已发布 */
    private long published;
    /** 待审核 */
    private long pending;
    /** 草稿 */
    private long draft;
    /** 已驳回 */
    private long rejected;
    /** 已下架 */
    private long offline;
    /** 涉敏目录数（密级≥3 或含敏感字段） */
    private long sensitive;
    /** 数据项总数 */
    private long itemTotal;
    /** 数据源总数 */
    private long sourceTotal;
    /** 分类节点数 */
    private long classifyTotal;
    /** 按状态分布：[{code,label,count}] */
    private List<Map<String, Object>> byStatus = new ArrayList<>();
    /** 按部门分布：[{deptId,deptName,count}] */
    private List<Map<String, Object>> byDept = new ArrayList<>();
    /** 按共享属性分布：[{code,label,count}] */
    private List<Map<String, Object>> byShareType = new ArrayList<>();
    /** 按密级分布：[{level,label,count}] */
    private List<Map<String, Object>> bySecretLevel = new ArrayList<>();
    private LocalDateTime statTime;
}
