package com.share.agent.dto;

import lombok.Getter;
import lombok.Setter;

/** 节点心跳请求。 */
@Getter
@Setter
public class AgentHeartbeatRequest {

    /** 节点编码（注册接口返回的 nodeId） */
    private String nodeId;

    /** 节点令牌 */
    private String nodeToken;

    /** CPU 使用率（%） */
    private Double cpu;

    /** 内存使用率（%） */
    private Double mem;

    /** 磁盘使用率（%） */
    private Double disk;

    /** 节点自报状态：ONLINE（默认）/ OFFLINE */
    private String status;

    /** 附带信息 */
    private String message;
}
