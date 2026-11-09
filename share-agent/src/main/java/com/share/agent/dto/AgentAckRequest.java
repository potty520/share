package com.share.agent.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 命令执行结果回传请求。 */
@Getter
@Setter
public class AgentAckRequest {

    /** 节点编码 */
    private String nodeId;

    /** 节点令牌 */
    private String nodeToken;

    /** 是否执行成功 */
    private Boolean success;

    /** 执行结果 */
    private Map<String, Object> result;

    /** 失败原因/备注 */
    private String message;
}
