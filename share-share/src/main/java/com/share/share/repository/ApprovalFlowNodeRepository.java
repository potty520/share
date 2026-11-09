package com.share.share.repository;

import com.share.share.entity.ApprovalFlowNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 审批节点轨迹仓储。 */
public interface ApprovalFlowNodeRepository extends JpaRepository<ApprovalFlowNode, Long> {

    List<ApprovalFlowNode> findByApplyIdOrderByNodeNoAscIdAsc(Long applyId);

    Optional<ApprovalFlowNode> findByApplyIdAndNodeNo(Long applyId, Integer nodeNo);

    List<ApprovalFlowNode> findByApplyIdAndStatus(Long applyId, String status);

    List<ApprovalFlowNode> findByAssigneeIdAndStatus(Long assigneeId, String status);
}
