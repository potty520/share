package com.share.security.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 分类分级模板（FR-10）：1~4 级定义以 JSON 存储，是目录/字段打标的依据。
 */
@Getter
@Setter
@Entity
@Table(name = "sec_classify_template")
public class SecClassifyTemplate extends BaseEntity {

    @Column(name = "template_code", nullable = false, length = 64)
    private String templateCode;

    @Column(name = "template_name", nullable = false, length = 100)
    private String templateName;

    @Column(name = "description", length = 500)
    private String description;

    /** 1~4 级定义 JSON，如 [{"level":1,"name":"公开","color":"#67C23A"}]（TEXT 避免超长） */
    @Column(name = "level_defs", columnDefinition = "TEXT")
    private String levelDefs;

    /** 1 启用 / 0 停用 */
    @Column(name = "status")
    private Integer status = 1;

    /** 内置模板不可删除 */
    @Column(name = "builtin")
    private Boolean builtin = false;
}
