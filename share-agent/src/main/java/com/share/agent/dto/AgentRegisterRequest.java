package com.share.agent.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** 节点注册请求（FR-02 前置节点，安装凭证注册，无需 JWT）。 */
@Getter
@Setter
public class AgentRegisterRequest {

    /** 安装凭证（演示固定值 AGENT-INSTALL-2026） */
    private String installCode;

    /** 节点名称 */
    private String nodeName;

    /** 节点 IP */
    private String ip;

    /** 服务端口 */
    private Integer port;

    /** Agent 版本 */
    private String version;

    /** 操作系统信息 */
    private String osInfo;

    /** 能力清单 */
    private List<String> capabilities;

    /** 扩展信息 */
    private Map<String, Object> extra;
}
