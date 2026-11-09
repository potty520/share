package com.share.security.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 数据分级标签（FR-10）：可对目录（CATALOG）或字段（FIELD）打 1~4 级标签。
 * <p>FIELD 标签同时记录所属目录 catalogId（即 targetId）与字段名 fieldName。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "sec_classify_tag", indexes = {
        @Index(name = "idx_tag_target", columnList = "target_type,target_id")
})
public class SecClassifyTag extends BaseEntity {

    /** 打标目标类型：CATALOG / FIELD */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    /** 目录 id（FIELD 时代表所属目录 id） */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 目标名称（目录名或字段中文名），便于展示 */
    @Column(name = "target_name", length = 200)
    private String targetName;

    @Column(name = "template_id")
    private Long templateId;

    /** 字段名（仅 FIELD 类型有值；列名避开保留字） */
    @Column(name = "field_name", length = 100)
    private String fieldName;

    /** 密级 1~4，4 级最高 */
    @Column(name = "classify_level")
    private Integer level;

    @Column(name = "level_name", length = 32)
    private String levelName;

    /** 打标来源：MANUAL 手工 / AUTO 规则自动 */
    @Column(name = "tag_source", length = 16)
    private String tagSource = "MANUAL";

    @Column(name = "tagged_by", length = 64)
    private String taggedBy;

    @Column(name = "tagged_time")
    private LocalDateTime taggedTime;

    @Column(name = "remark", length = 500)
    private String remark;
}
