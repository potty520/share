package com.share.share.service;

import com.share.common.constant.SeedConstants;
import com.share.common.event.NotifyEvent;
import com.share.common.util.JsonUtil;
import com.share.share.constant.ShareConstants;
import com.share.share.entity.ApprovalApply;
import com.share.share.entity.ApprovalFlowNode;
import com.share.share.entity.ApprovalGrant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 审批/授权通知服务（FR-14 事件通知）。
 * <p>通过 {@link NotifyEvent} 发布到站内消息中心（share-ops 消费落库），
 * 审批待办、审批结果、授权到期提醒统一走此出口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalNotifyService {

    private final ApplicationEventPublisher publisher;

    /** 通知指定用户。 */
    public void notifyUser(Long receiverId, String title, String content, String bizType,
                           String bizId, String level, String link) {
        if (receiverId == null) {
            log.debug("通知接收人为空，跳过: {}", title);
            return;
        }
        try {
            publisher.publishEvent(NotifyEvent.builder()
                    .receiverId(receiverId)
                    .title(title)
                    .content(content)
                    .bizType(bizType)
                    .bizId(bizId)
                    .channel("INNER")
                    .level(level == null ? "INFO" : level)
                    .link(link)
                    .build());
        } catch (Exception e) {
            log.warn("发布通知事件失败: title={} err={}", title, e.getMessage());
        }
    }

    /** 通知申请人审批结果。 */
    public void notifyApplicant(ApprovalApply apply, String title, String content, String level) {
        notifyUser(apply.getApplicantId(), title, content, "APPROVAL", apply.getApplyNo(), level,
                "/approval/my");
    }

    /** 通知当前节点审批人（按角色映射演示账号；真实环境应查询角色下的实际用户）。 */
    public void notifyApprover(ApprovalApply apply, ApprovalFlowNode node) {
        Long receiverId = node.getAssigneeId() != null ? node.getAssigneeId()
                : ShareConstants.demoUserIdOfRole(node.getRoleCode());
        notifyUser(receiverId, "共享数据申请待审批",
                "申请单 " + apply.getApplyNo() + "（" + safe(apply.getCatalogName(), apply.getApiName())
                        + "）待您审批，节点：" + node.getNodeName(),
                "APPROVAL", apply.getApplyNo(), "WARN", "/approval/todo");
    }

    /** 通知授权到期/回收。 */
    public void notifyGrant(ApprovalGrant grant, String title, String content, String level) {
        notifyUser(grant.getConsumerId(), title, content, "EXPIRY", grant.getGrantNo(), level,
                "/approval/grants");
    }

    /** 审批明细快照（写入审计/通知正文时使用，避免超长）。 */
    public String snapshot(Map<String, Object> data) {
        String json = JsonUtil.toJson(data);
        return json == null ? null : (json.length() > 500 ? json.substring(0, 500) + "..." : json);
    }

    private String safe(String a, String b) {
        return a != null ? a : (b != null ? b : "共享资源");
    }

    /** 演示：按角色取账号用户名（通知正文展示用）。 */
    public String usernameOfRole(String roleCode) {
        return ShareConstants.demoUsernameOfRole(roleCode);
    }

    /** 演示角色常量（避免调用方重复引入常量类）。 */
    public String roleOfBizAdmin() {
        return SeedConstants.ROLE_BIZ_ADMIN;
    }
}
