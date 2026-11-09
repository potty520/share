package com.share.task.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务依赖（FR-04 DAG）：以邻接表方式保存“下游任务 → 上游任务”的边。
 * <p>taskId 为下游（后置）任务，upTaskId 为上游（前置）任务；保存时做环检测。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "tk_task_dep")
public class TkTaskDep extends BaseEntity {

    /** 下游任务 ID（依赖方） */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 上游任务 ID（被依赖方） */
    @Column(name = "up_task_id", nullable = false)
    private Long upTaskId;

    /** 依赖类型：SUCCESS（前置成功后触发）/ ALWAYS */
    @Column(name = "dep_type", length = 20)
    private String depType = "SUCCESS";

    /** 上游任务名称（冗余展示） */
    @Column(name = "up_task_name", length = 200)
    private String upTaskName;
}
