package com.share.catalog.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据源变更历史（FR-01：变更历史记录）。
 * <p>新增 / 修改 / 启停 / 连通测试 / 删除 均落一条记录，操作人与时间取自 BaseEntity。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "ds_source_log", indexes = {
        @Index(name = "idx_ds_source_log_source", columnList = "source_id")
})
public class DataSourceLog extends BaseEntity {

    /** 所属数据源 ID */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** 数据源编码（冗余，便于检索） */
    @Column(name = "source_code", length = 64)
    private String sourceCode;

    /** 数据源名称 */
    @Column(name = "source_name", length = 128)
    private String sourceName;

    /** 动作：CREATE/UPDATE/STATUS/TEST/DELETE */
    @Column(length = 32)
    private String action;

    /** 动作中文说明 */
    @Column(name = "action_desc", length = 64)
    private String actionDesc;

    /** 变更明细（字段级差异摘要） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 结果：SUCCESS / FAIL */
    @Column(length = 16)
    private String result;
}
