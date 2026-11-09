package com.share.share.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 共享申请单（FR-08，表 {@code apr_apply}）。
 * <p>使用方按目录资源或共享 API 提交申请，填写用途/范围/期限/数据项范围；
 * 提交时按数据密级自动生成审批节点（涉敏追加安全管理员节点）。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "apr_apply",
        uniqueConstraints = @UniqueConstraint(name = "uk_apr_apply_no", columnNames = "apply_no"),
        indexes = {
                @Index(name = "idx_apr_apply_status", columnList = "status"),
                @Index(name = "idx_apr_apply_applicant", columnList = "applicant_id")
        })
public class ApprovalApply extends BaseEntity {

    /** 申请单号（IdGen.applyNo） */
    @Column(name = "apply_no", nullable = false, length = 64)
    private String applyNo;

    /** 申请的资源目录 ID */
    @Column(name = "catalog_id")
    private Long catalogId;

    @Column(name = "catalog_name", length = 128)
    private String catalogName;

    /** 申请的共享 API ID */
    @Column(name = "api_id")
    private Long apiId;

    @Column(name = "api_code", length = 64)
    private String apiCode;

    @Column(name = "api_name", length = 128)
    private String apiName;

    /** 申请人 */
    @Column(name = "applicant_id")
    private Long applicantId;

    @Column(name = "applicant_name", length = 64)
    private String applicantName;

    @Column(name = "applicant_dept_id")
    private Long applicantDeptId;

    /** 申请用途 */
    @Column(name = "apply_purpose", columnDefinition = "TEXT")
    private String purpose;

    /** 使用范围 */
    @Column(name = "apply_scope", columnDefinition = "TEXT")
    private String scope;

    /** 使用起始日期 */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 使用截止日期 */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** 数据项范围（逗号分隔字段） */
    @Column(name = "item_scope", columnDefinition = "TEXT")
    private String itemScope;

    /** 数据密级（跨域只读 cat_catalog 得到，决定审批节点数与动态脱敏） */
    @Column(name = "data_level")
    private Integer level = 1;

    /** 是否涉敏（level >= share.security.sensitive-level） */
    @Column(name = "is_sensitive")
    private Boolean sensitive = false;

    /** 状态：PENDING / APPROVED / REJECTED / WITHDRAWN */
    @Column(name = "status", length = 20)
    private String status = "PENDING";

    /** 当前审批节点序号 */
    @Column(name = "current_node_no")
    private Integer currentNodeNo = 1;

    /** 总节点数 */
    @Column(name = "total_nodes")
    private Integer totalNodes = 1;

    /** 当前节点名称 */
    @Column(name = "current_node_name", length = 64)
    private String currentNodeName;

    /** 当前节点待审角色 */
    @Column(name = "current_node_role", length = 32)
    private String currentNodeRole;

    /** 当前节点指定办理人（转办后不为空） */
    @Column(name = "current_assignee_id")
    private Long currentNodeAssigneeId;

    @Column(name = "current_assignee_name", length = 64)
    private String currentNodeAssigneeName;

    /** 审批通过时间 */
    @Column(name = "approve_time")
    private LocalDateTime approveTime;

    /** 驳回原因 */
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** 审批通过后签发的凭据 */
    @Column(name = "credential_id")
    private Long credentialId;

    /** 审批通过后生成的授权 */
    @Column(name = "grant_id")
    private Long grantId;

    /** 备注 */
    @Column(name = "remark", length = 500)
    private String remark;
}
