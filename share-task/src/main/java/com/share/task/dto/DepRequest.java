package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 任务依赖保存请求（FR-04 DAG）。 */
@Getter
@Setter
public class DepRequest {

    /** 批量上游任务 ID */
    private List<Long> upTaskIds;

    /** 单个上游任务 ID（兼容简化写法） */
    private Long upTaskId;

    /** 依赖类型：SUCCESS / ALWAYS */
    private String depType;
}
