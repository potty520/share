package com.share.catalog.dto;

import com.share.catalog.entity.CatalogVersion;
import com.share.common.util.JsonUtil;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 目录版本响应对象（快照以 JSON 对象返回，便于前端展示版本差异）。 */
@Getter
@Setter
public class CatalogVersionVO {

    private Long id;
    private Long catalogId;
    private Integer versionNo;
    private String changeType;
    private String changeLog;
    private LocalDateTime publishTime;
    private String operator;
    private LocalDateTime createTime;
    /** 版本快照内容（JSON 对象） */
    private Object snapshot;

    public static CatalogVersionVO from(CatalogVersion e) {
        CatalogVersionVO vo = new CatalogVersionVO();
        vo.id = e.getId();
        vo.catalogId = e.getCatalogId();
        vo.versionNo = e.getVersionNo();
        vo.changeType = e.getChangeType();
        vo.changeLog = e.getChangeLog();
        vo.publishTime = e.getPublishTime();
        vo.operator = e.getOperator() != null ? e.getOperator() : e.getCreateBy();
        vo.createTime = e.getCreateTime();
        String json = e.getSnapshot();
        if (json != null && !json.isBlank()) {
            try {
                vo.snapshot = JsonUtil.mapper().readTree(json);
            } catch (Exception ignore) {
                vo.snapshot = json;
            }
        }
        return vo;
    }
}
