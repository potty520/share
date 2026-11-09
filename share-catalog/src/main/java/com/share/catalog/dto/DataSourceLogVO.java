package com.share.catalog.dto;

import com.share.catalog.entity.DataSourceLog;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 数据源变更历史响应对象。 */
@Getter
@Setter
public class DataSourceLogVO {

    private Long id;
    private Long sourceId;
    private String sourceCode;
    private String sourceName;
    private String action;
    private String actionDesc;
    private String detail;
    private String result;
    /** 操作人（取自 BaseEntity.createBy） */
    private String operator;
    private LocalDateTime operateTime;

    public static DataSourceLogVO from(DataSourceLog e) {
        DataSourceLogVO vo = new DataSourceLogVO();
        vo.id = e.getId();
        vo.sourceId = e.getSourceId();
        vo.sourceCode = e.getSourceCode();
        vo.sourceName = e.getSourceName();
        vo.action = e.getAction();
        vo.actionDesc = e.getActionDesc();
        vo.detail = e.getDetail();
        vo.result = e.getResult();
        vo.operator = e.getCreateBy();
        vo.operateTime = e.getCreateTime();
        return vo;
    }
}
