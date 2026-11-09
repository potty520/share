package com.share.share.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 审批节点轨迹（FR-08，表 {@code apr_flow_node}）。
 * <p>申请单提交时一次性生成全部节点；审批推进只更新节点状态，保证全流程留痕可查。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "apr_flow_node", indexes = {
        @Index(name = "idx_apr_node_apply", columnList = "apply_id"),
        @Index(name = "idx_apr_node_status", columnList = "status")
})
public class ApprovalFlowNode extends BaseEntity {

    @Column(name = "apply_id", nullable = false)
    private Long applyId;

    @Column(name = "apply_no", length = 64)
    private String applyNo;

    /** 节点序号，从 1 开始 */
    @Column(name = "node_no", nullable = false)
    private Integer nodeNo;

    /** 节点名称，如“业务管理员审批” */
    @Column(name = "node_name", length = 64)
    private String nodeName;

    /** 节点类型：BIZ_ADMIN / PROVIDER / SECURITY / RENEW */
    @Column(name = "node_type", length = 32)
    private String nodeType;

    /** 待审角色 */
    @Column(name = "role_code", length = 32)
    private String roleCode;

    /** 节点状态：PENDING / APPROVED / REJECTED / SKIPPED */
    @Column(name = "status", length = 20)
    private String status = "PENDING";

    /** 指定办理人（转办后指向被转办人） */
    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "assignee_name", length = 64)
    private String assigneeName;

    /** 转办来源 */
    @Column(name = "transfer_from_id")
    private Long transferFromId;

    @Column(name = "transfer_from_name", length = 64)
    private String transferFromName;

    /** 转办目标 */
    @Column(name = "transfer_to_id")
    private Long transferToId;

    @Column(name = "transfer_to_name", length = 64)
    private String transferToName;

    /** 实际办理人 */
    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "approver_name", length = 64)
    private String approverName;

    /** 审批意见 */
    @Column(name = "comment_content", columnDefinition = "TEXT")
    private String comment;

    /** 办理时间 */
    @Column(name = "handle_time")
    private LocalDateTime handleTime;
}
