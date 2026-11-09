package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务定义版本（FR-04 / FR-05 加工规则版本管理）：
 * 每次创建/修改任务都追加一个不可变快照，便于回溯与比对。
 */
@Getter
@Setter
@Entity
@Table(name = "tk_task_version")
public class TkTaskVersion extends BaseEntity {

    /** 所属任务 ID */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 任务编码（冗余，便于按编码检索历史） */
    @Column(name = "task_code", length = 64)
    private String taskCode;

    /** 版本号，从 1 递增 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 变更类型：CREATE / UPDATE */
    @Column(name = "change_type", length = 20)
    private String changeType;

    /** 该版本的完整任务定义快照 JSON */
    @Column(name = "definition_json", columnDefinition = "TEXT")
    private String definitionJson;

    /** 变更说明 */
    @Column(name = "change_note", length = 500)
    private String changeNote;
}
