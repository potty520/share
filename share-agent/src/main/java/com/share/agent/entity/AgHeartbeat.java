package com.share.agent.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 节点心跳历史（FR-02 / FR-12）：记录每次上报的资源占用与存活状态。 */
@Getter
@Setter
@Entity
@Table(name = "ag_heartbeat")
public class AgHeartbeat extends BaseEntity {

    /** 节点编码 */
    @Column(name = "node_code", length = 64)
    private String nodeCode;

    /** 节点名称（冗余） */
    @Column(name = "node_name", length = 200)
    private String nodeName;

    /** 上报 IP */
    @Column(name = "ip", length = 64)
    private String ip;

    /** CPU 使用率（%） */
    @Column(name = "cpu_usage")
    private Double cpuUsage;

    /** 内存使用率（%） */
    @Column(name = "mem_usage")
    private Double memUsage;

    /** 磁盘使用率（%） */
    @Column(name = "disk_usage")
    private Double diskUsage;

    /** 上报时节点状态 */
    @Column(name = "status", length = 20)
    private String status;

    /** 上报时间 */
    @Column(name = "report_time")
    private LocalDateTime reportTime;

    /** 附带信息（如正在执行的任务、错误信息） */
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;
}
