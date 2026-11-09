package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 批量启停任务请求。 */
@Getter
@Setter
public class BatchEnableRequest {

    /** 任务 ID 列表 */
    private List<Long> ids;

    /** 目标启用状态 */
    private Boolean enabled;
}
