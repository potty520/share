package com.share.agent.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 管理端下发命令请求。 */
@Getter
@Setter
public class AgentDispatchRequest {

    /** 目标节点编码 */
    private String nodeId;

    /** 命令类型：TASK_RUN / TASK_STOP / FILE_PUSH / PACKAGE_IMPORT / UPGRADE */
    private String commandType;

    /** 命令标题 */
    private String title;

    /** 命令负载 */
    private Map<String, Object> payload;

    /** 优先级，默认 5 */
    private Integer priority;

    /** 过期分钟数，默认 30 */
    private Integer expireMinutes;
}
