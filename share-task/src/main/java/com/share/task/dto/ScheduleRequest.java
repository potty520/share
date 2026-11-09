package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

/** 调度设置请求（FR-04 调度策略）。 */
@Getter
@Setter
public class ScheduleRequest {

    /** Spring 6 段 cron 表达式，如 0 0 2 * * ? */
    private String cron;

    /** 是否启用调度（关闭时清空调度但保留任务） */
    private Boolean enabled;
}
