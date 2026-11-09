package com.share.agent.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 前置节点（FR-02 前置节点）：记录节点身份、安装凭证、证书指纹、存活状态与资源占用。
 * <p>列名与 share-ops 监控域约定的 ag_node 只读列保持一致（node_code/node_name/cpu_usage/...）。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "ag_node")
public class AgNode extends BaseEntity {

    /** 节点编码（中心下发的全局唯一节点 ID，如 AG20261109143001001） */
    @Column(name = "node_code", length = 64, nullable = false)
    private String nodeCode;

    /** 节点名称 */
    @Column(name = "node_name", length = 200, nullable = false)
    private String nodeName;

    /** 节点 IP */
    @Column(name = "ip", length = 64)
    private String ip;

    /** 服务端口 */
    @Column(name = "port")
    private Integer port;

    /** 能力清单 JSON：如 ["FILE_TRANSFER","TASK_RUN"] */
    @Column(name = "capabilities_json", columnDefinition = "TEXT")
    private String capabilitiesJson;

    /** 节点访问令牌（注册时下发，心跳/命令通道校验） */
    @Column(name = "node_token", length = 128)
    private String nodeToken;

    /** 节点证书指纹（SHA-256，演示环境模拟证书） */
    @Column(name = "cert_fingerprint", length = 200)
    private String certFingerprint;

    /** 节点状态：ONLINE / OFFLINE / DISABLED */
    @Column(name = "status", length = 20)
    private String status;

    /** Agent 版本 */
    @Column(name = "version", length = 32)
    private String version;

    /** 操作系统信息 */
    @Column(name = "os_info", length = 128)
    private String osInfo;

    /** CPU 使用率（%） */
    @Column(name = "cpu_usage")
    private Double cpuUsage = 0D;

    /** 内存使用率（%） */
    @Column(name = "mem_usage")
    private Double memUsage = 0D;

    /** 磁盘使用率（%） */
    @Column(name = "disk_usage")
    private Double diskUsage = 0D;

    /** 最近心跳时间 */
    @Column(name = "last_heartbeat_time")
    private LocalDateTime lastHeartbeatTime;

    /** 注册时间 */
    @Column(name = "register_time")
    private LocalDateTime registerTime;

    /** 累计执行命令数 */
    @Column(name = "tasks_done")
    private Long tasksDone = 0L;

    /** 安装凭证（演示固定值，正式环境应一机一码） */
    @Column(name = "install_code", length = 64)
    private String installCode;

    /** 备注 */
    @Column(name = "remark", length = 500)
    private String remark;
}
