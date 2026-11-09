package com.share.catalog.dto;

import com.share.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 数据源列表查询参数。 */
@Getter
@Setter
public class DataSourceQuery extends PageQuery {

    /** 关键词：编码/名称/主机/责任人 */
    private String keyword;

    /** 类型：MYSQL/ORACLE/… */
    private String type;

    /** 状态：ENABLED/DISABLED */
    private String status;

    /** 归属部门 */
    private Long deptId;
}
