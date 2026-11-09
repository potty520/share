package com.share.agent.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 节点命令（FR-02 任务下发）：中心下发给前置节点的任务指令，
 * 通过长轮询通道下发，节点 ACK 后回传执行结果。
 */
@Getter
@Setter
@Entity
@Table(name = "ag_command")
public class AgCommand extends BaseEntity {

    /** 命令 ID */
    @Column(name = "command_id", length = 64, nullable = false)
    private String commandId;

    /** 目标节点编码 */
    @Column(name = "node_code", length = 64)
    private String nodeCode;

    /** 节点名称（冗余） */
    @Column(name = "node_name", length = 200)
    private String nodeName;

    /** 命令类型：TASK_RUN / TASK_STOP / FILE_PUSH / PACKAGE_IMPORT / UPGRADE */
    @Column(name = "command_type", length = 32)
    private String commandType;

    /** 命令标题 */
    @Column(name = "title", length = 200)
    private String title;

    /** 命令负载 JSON（如任务 ID、文件清单） */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /** 命令状态：PENDING / SENT / SUCCESS / FAILED / EXPIRED */
    @Column(name = "status", length = 20)
    private String status = "PENDING";

    /** 优先级（数值越大越优先） */
    @Column(name = "priority")
    private Integer priority = 5;

    /** 已重发次数 */
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    /** 创建人用户 ID */
    @Column(name = "create_by_user")
    private Long createByUser;

    /** 创建人用户名 */
    @Column(name = "create_by_name", length = 64)
    private String createByName;

    /** 下发时间 */
    @Column(name = "send_time")
    private LocalDateTime sendTime;

    /** ACK 时间 */
    @Column(name = "ack_time")
    private LocalDateTime ackTime;

    /** 过期时间 */
    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    /** 执行结果 JSON */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;
}
