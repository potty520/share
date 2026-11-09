package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

/** 手工触发/重跑返回：实例与批次句柄。 */
@Getter
@Setter
public class RunTicket {

    private Long instanceId;
    private String batchId;
    private Long taskId;
    private String taskName;
    private String status;

    public RunTicket() {
    }

    public RunTicket(Long taskId, String taskName, Long instanceId, String batchId, String status) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.instanceId = instanceId;
        this.batchId = batchId;
        this.status = status;
    }
}
