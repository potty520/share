package com.share.ops.monitor.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 节点监控快照（FR-12）：中心平台与前置节点的存活、CPU/内存/磁盘。
 * <p>节点身份以 share-agent 的 {@code ag_node.node_code} 为准，本表保存监控视角的资源快照。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "mon_node", indexes = {
        @Index(name = "idx_mon_node_code", columnList = "node_code"),
        @Index(name = "idx_mon_node_status", columnList = "status")
})
public class MonNode extends BaseEntity {

    /** 节点编码，如 EDGE-001（与前置节点注册编码一致） */
    @Column(name = "node_code", nullable = false, length = 64)
    private String nodeCode;

    @Column(name = "node_name", length = 200)
    private String nodeName;

    /** 节点类型：CENTER（中心）/ EDGE（前置） */
    @Column(name = "node_type", length = 16)
    private String nodeType;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "port")
    private Integer port;

    /** ONLINE / OFFLINE / DEGRADED */
    @Column(name = "status", length = 16)
    private String status;

    /** CPU 使用率（%） */
    @Column(name = "cpu_usage")
    private Double cpuUsage;

    /** 内存使用率（%） */
    @Column(name = "mem_usage")
    private Double memUsage;

    /** 磁盘使用率（%） */
    @Column(name = "disk_usage")
    private Double diskUsage;

    /** 入向流量（KB/s） */
    @Column(name = "net_in")
    private Double netIn;

    /** 出向流量（KB/s） */
    @Column(name = "net_out")
    private Double netOut;

    @Column(name = "version", length = 32)
    private String version;

    /** 最近心跳时间 */
    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "node_group", length = 64)
    private String nodeGroup;

    /** 当前执行任务数 */
    @Column(name = "running_tasks")
    private Integer runningTasks;

    /** 演示模拟节点：由平台心跳刷新任务维持在线状态（仅种子数据使用） */
    @Column(name = "simulated")
    private Boolean simulated = false;

    @Column(name = "remark", length = 500)
    private String remark;
}
