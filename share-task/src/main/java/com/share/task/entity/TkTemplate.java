package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务模板（FR-04）：把常用源/映射/目标配置沉淀为模板，创建任务时可一键套用。
 */
@Getter
@Setter
@Entity
@Table(name = "tk_template")
public class TkTemplate extends BaseEntity {

    /** 模板编码 */
    @Column(name = "template_code", length = 64, nullable = false)
    private String templateCode;

    /** 模板名称 */
    @Column(name = "template_name", length = 200, nullable = false)
    private String templateName;

    /** 适用任务类型：FULL / INCREMENT */
    @Column(name = "task_type", length = 20)
    private String taskType;

    /** 模板分类：库表同步 / 文件同步 / 增量同步 等 */
    @Column(name = "category", length = 64)
    private String category;

    /** 模板定义 JSON（可直接套用为任务定义） */
    @Column(name = "definition_json", columnDefinition = "TEXT")
    private String definitionJson;

    /** 模板说明 */
    @Column(name = "description", length = 500)
    private String description;

    /** 是否启用 */
    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    /** 被套用次数 */
    @Column(name = "used_count")
    private Integer usedCount = 0;
}
