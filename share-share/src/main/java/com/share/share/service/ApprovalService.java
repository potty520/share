package com.share.share.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.security.LoginUser;
import com.share.common.security.UserContext;
import com.share.common.util.IdGen;
import com.share.common.util.JsonUtil;
import com.share.share.constant.ShareConstants;
import com.share.share.dto.ApprovalActionRequest;
import com.share.share.dto.ApprovalSubmitRequest;
import com.share.share.entity.ApprovalApply;
import com.share.share.entity.ApprovalFlowNode;
import com.share.share.entity.ApprovalGrant;
import com.share.share.repository.ApprovalApplyRepository;
import com.share.share.repository.ApprovalFlowNodeRepository;
import com.share.share.repository.ApprovalGrantRepository;
import com.share.share.repository.ShareApiRepository;
import com.share.share.support.CrossDomainQueryService;
import com.share.share.support.PageResult;
import com.share.share.support.ShareUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 共享申请与多级审批服务（FR-08）。
 * <p>闭环流程：使用方提交申请 → 按数据密级自动生成审批节点（业务管理员 → 数据提供方 →
 * 安全管理员[涉敏]）→ 逐级审批（驳回/撤回/转办）→ 全部通过自动签发凭据与授权（含到期时间）
 * → 到期提醒/回收 → 支持续期。全流程节点轨迹落库可查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalApplyRepository applyRepository;
    private final ApprovalFlowNodeRepository nodeRepository;
    private final ApprovalGrantRepository grantRepository;
    private final ShareApiRepository apiRepository;
    private final CrossDomainQueryService crossDomainQueryService;
    private final GrantService grantService;
    private final ShareCredentialService credentialService;
    private final ApprovalNotifyService notifyService;

    /** 涉敏判定阈值（与安全域保持一致，默认 3 级及以上视为涉敏）。 */
    @Value("${share.security.sensitive-level:3}")
    private int sensitiveLevel = 3;

    /** 审批节点定义。 */
    public record NodeDef(String nodeType, String nodeName, String roleCode) {
    }

    // ==================== 提交 / 查询 ====================

    /** 提交共享申请：自动生成申请单号与审批节点。 */
    @Transactional
    public Map<String, Object> submit(ApprovalSubmitRequest req) {
        LoginUser user = requireUser();
        if (req.catalogId() == null && req.apiId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择申请的目录资源或共享服务");
        }
        Long catalogId = req.catalogId();
        String catalogName = null;
        String apiCode = null;
        String apiName = null;
        Long apiId = req.apiId();
        ShareApiRef apiRef = null;
        if (apiId != null) {
            var api = apiRepository.findByIdAndDeletedFalse(apiId)
                    .orElseThrow(() -> new BizException(ErrorCode.SHARE_API_NOT_FOUND));
            apiRef = new ShareApiRef(api.getId(), api.getApiCode(), api.getApiName(), api.getCatalogId(),
                    api.getCatalogName(), api.getQpsLimit());
            apiCode = api.getApiCode();
            apiName = api.getApiName();
            if (catalogId == null) {
                catalogId = api.getCatalogId();
            }
            catalogName = api.getCatalogName();
        }
        int level = 1;
        if (catalogId != null) {
            var catalog = crossDomainQueryService.findCatalog(catalogId);
            if (catalog.isPresent()) {
                if (catalogName == null) {
                    catalogName = catalog.get().name();
                }
                level = catalog.get().level() == null ? 1 : catalog.get().level();
            } else if (catalogName == null) {
                catalogName = "目录资源#" + catalogId;
            }
        }
        if (catalogName == null) {
            catalogName = apiName;
        }
        boolean sensitive = level >= Math.max(sensitiveLevel, 1);

        LocalDate startDate = ShareUtils.parseDate(req.startDate());
        LocalDate endDate = ShareUtils.parseDate(req.endDate());
        if (startDate == null) {
            startDate = LocalDate.now();
        }
        if (endDate == null) {
            endDate = startDate.plusDays(90);
        }
        if (endDate.isBefore(startDate)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "使用截止日期不能早于起始日期");
        }
        if (endDate.isBefore(LocalDate.now())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "使用截止日期不能早于今天");
        }

        ApprovalApply apply = new ApprovalApply();
        apply.setApplyNo(IdGen.applyNo());
        apply.setCatalogId(catalogId);
        apply.setCatalogName(ShareUtils.truncate(catalogName, 128));
        apply.setApiId(apiRef == null ? null : apiRef.id());
        apply.setApiCode(apiCode);
        apply.setApiName(apiName);
        apply.setApplicantId(user.getUserId());
        apply.setApplicantName(ShareUtils.truncate(user.getUsername(), 64));
        apply.setApplicantDeptId(user.getDeptId());
        apply.setPurpose(req.purpose());
        apply.setScope(req.scope());
        apply.setStartDate(startDate);
        apply.setEndDate(endDate);
        apply.setItemScope(req.itemScope());
        apply.setLevel(level);
        apply.setSensitive(sensitive);
        apply.setStatus(ShareConstants.APPLY_PENDING);
        List<NodeDef> defs = nodeDefs(sensitive);
        apply.setCurrentNodeNo(1);
        apply.setTotalNodes(defs.size());
        apply.setCurrentNodeName(defs.get(0).nodeName());
        apply.setCurrentNodeRole(defs.get(0).roleCode());
        ApprovalApply saved = applyRepository.save(apply);

        List<ApprovalFlowNode> nodes = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            NodeDef def = defs.get(i);
            ApprovalFlowNode node = new ApprovalFlowNode();
            node.setApplyId(saved.getId());
            node.setApplyNo(saved.getApplyNo());
            node.setNodeNo(i + 1);
            node.setNodeName(def.nodeName());
            node.setNodeType(def.nodeType());
            node.setRoleCode(def.roleCode());
            node.setStatus(ShareConstants.NODE_PENDING);
            nodes.add(nodeRepository.save(node));
        }
        notifyService.notifyApplicant(saved, "共享数据申请已提交",
                "申请单 " + saved.getApplyNo() + "（" + catalogName + "）已提交，共 "
                        + defs.size() + " 个审批节点" + (sensitive ? "（涉敏数据需安全管理员审批）" : "")
                        + "，请等待审批。", "INFO");
        notifyService.notifyApprover(saved, nodes.get(0));
        log.info("提交共享申请 applyNo={} catalog={} level={} sensitive={} nodes={}", saved.getApplyNo(),
                catalogName, level, sensitive, defs.size());
        return detail(saved.getId());
    }

    /** 分页查询：status / mine / todo（当前节点角色匹配） / keyword。 */
    public PageResult<ApprovalApply> list(String status, Boolean mine, Boolean todo, String keyword,
                                          int pageNum, int pageSize) {
        LoginUser user = requireUser();
        Long uid = user.getUserId();
        Specification<ApprovalApply> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            if (Boolean.TRUE.equals(mine)) {
                ps.add(uid == null ? cb.disjunction() : cb.equal(root.get("applicantId"), uid));
            }
            if (Boolean.TRUE.equals(todo)) {
                ps.add(cb.equal(root.get("status"), ShareConstants.APPLY_PENDING));
                if (!user.isAdmin()) {
                    Predicate byRole = user.getRoleCode() == null ? cb.disjunction()
                            : cb.equal(root.get("currentNodeRole"), user.getRoleCode());
                    Predicate byAssignee = uid == null ? cb.disjunction()
                            : cb.equal(root.get("currentNodeAssigneeId"), uid);
                    ps.add(cb.or(byRole, byAssignee));
                }
            }
            if (!Boolean.TRUE.equals(mine) && !Boolean.TRUE.equals(todo) && !user.isAdmin() && !isApproverRole(user)) {
                // 普通使用方只能看自己的申请
                ps.add(uid == null ? cb.disjunction() : cb.equal(root.get("applicantId"), uid));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("applyNo")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("catalogName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("applicantName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("apiCode"), "")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        int num = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Page<ApprovalApply> page = applyRepository.findAll(spec,
                PageRequest.of(num - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.of(page.getContent(), page.getTotalElements(), num, size);
    }

    /** 申请详情：含审批节点轨迹、授权、凭据与可执行动作标记。 */
    public Map<String, Object> detail(Long id) {
        ApprovalApply apply = findOrThrow(id);
        LoginUser user = requireUser();
        checkReadPermission(apply, user);
        Map<String, Object> data = toMap(apply);
        List<ApprovalFlowNode> nodes = nodeRepository.findByApplyIdOrderByNodeNoAscIdAsc(id);
        data.put("nodes", nodes);
        data.put("nodeCount", nodes.size());
        data.put("currentNode", nodes.stream()
                .filter(n -> n.getNodeNo() != null && n.getNodeNo().equals(apply.getCurrentNodeNo()))
                .findFirst().orElse(null));
        data.put("sensitiveFlow", Boolean.TRUE.equals(apply.getSensitive()));
        data.put("sensitiveLevel", sensitiveLevel);
        if (apply.getGrantId() != null) {
            try {
                data.put("grant", grantService.detail(apply.getGrantId()));
            } catch (Exception e) {
                data.put("grant", null);
            }
        }
        if (apply.getCredentialId() != null) {
            try {
                data.put("credential", credentialService.toSafeMap(
                        credentialService.findOrThrow(apply.getCredentialId())));
            } catch (Exception e) {
                data.put("credential", null);
            }
        }
        data.put("canApprove", canApprove(apply, user));
        data.put("canWithdraw", ShareConstants.APPLY_PENDING.equals(apply.getStatus())
                && (user.isAdmin() || (apply.getApplicantId() != null
                && apply.getApplicantId().equals(user.getUserId()))));
        data.put("canRenew", ShareConstants.APPLY_APPROVED.equals(apply.getStatus())
                && apply.getGrantId() != null);
        return data;
    }

    // ==================== 审批动作 ====================

    /** 审批通过：推进当前节点；全部通过后自动签发凭据与授权。 */
    @Transactional
    public Map<String, Object> approve(Long id, ApprovalActionRequest req) {
        ApprovalApply apply = findOrThrow(id);
        requirePending(apply);
        LoginUser user = requireUser();
        ApprovalFlowNode node = currentPendingNode(apply);
        checkApprovePermission(apply, node, user);

        String comment = req == null ? null : ShareUtils.truncate(req.comment(), 500);
        node.setStatus(ShareConstants.NODE_APPROVED);
        node.setApproverId(user.getUserId());
        node.setApproverName(user.getUsername());
        node.setComment(comment);
        node.setHandleTime(LocalDateTime.now());
        nodeRepository.save(node);

        int nextNo = (apply.getCurrentNodeNo() == null ? 1 : apply.getCurrentNodeNo()) + 1;
        ApprovalFlowNode next = nodeRepository.findByApplyIdAndNodeNo(apply.getId(), nextNo).orElse(null);
        if (next != null) {
            apply.setCurrentNodeNo(nextNo);
            apply.setCurrentNodeName(next.getNodeName());
            apply.setCurrentNodeRole(next.getRoleCode());
            apply.setCurrentNodeAssigneeId(next.getAssigneeId());
            apply.setCurrentNodeAssigneeName(next.getAssigneeName());
            applyRepository.save(apply);
            notifyService.notifyApprover(apply, next);
            log.info("审批通过（非末级）applyNo={} node={} approver={}", apply.getApplyNo(),
                    node.getNodeName(), user.getUsername());
        } else {
            apply.setStatus(ShareConstants.APPLY_APPROVED);
            apply.setApproveTime(LocalDateTime.now());
            apply.setCurrentNodeNo(apply.getTotalNodes());
            apply.setCurrentNodeName("审批完成");
            apply.setCurrentNodeRole(null);
            apply.setCurrentNodeAssigneeId(null);
            apply.setCurrentNodeAssigneeName(null);
            applyRepository.save(apply);
            grantService.issueForApply(apply);
            log.info("审批全部通过并签发授权 applyNo={} level={}", apply.getApplyNo(), apply.getLevel());
        }
        return detail(id);
    }

    /** 驳回：当前节点置 REJECTED，申请单结束并通知申请人。 */
    @Transactional
    public Map<String, Object> reject(Long id, ApprovalActionRequest req) {
        ApprovalApply apply = findOrThrow(id);
        requirePending(apply);
        LoginUser user = requireUser();
        ApprovalFlowNode node = currentPendingNode(apply);
        checkApprovePermission(apply, node, user);
        String comment = req == null ? null : req.comment();
        if (comment == null || comment.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "驳回必须填写审批意见");
        }
        node.setStatus(ShareConstants.NODE_REJECTED);
        node.setApproverId(user.getUserId());
        node.setApproverName(user.getUsername());
        node.setComment(ShareUtils.truncate(comment, 500));
        node.setHandleTime(LocalDateTime.now());
        nodeRepository.save(node);

        apply.setStatus(ShareConstants.APPLY_REJECTED);
        apply.setRejectReason(ShareUtils.truncate(comment, 500));
        apply.setCurrentNodeName("已驳回");
        apply.setCurrentNodeRole(null);
        apply.setCurrentNodeAssigneeId(null);
        apply.setCurrentNodeAssigneeName(null);
        applyRepository.save(apply);
        skipPendingNodes(apply.getId(), node.getId());

        notifyService.notifyApplicant(apply, "共享数据申请被驳回",
                "申请单 " + apply.getApplyNo() + "（" + safeName(apply) + "）被 " + user.getUsername()
                        + " 驳回，意见：" + comment, "WARN");
        log.info("驳回共享申请 applyNo={} by={} reason={}", apply.getApplyNo(), user.getUsername(), comment);
        return detail(id);
    }

    /** 撤回：申请人撤回待审批的申请。 */
    @Transactional
    public Map<String, Object> withdraw(Long id) {
        ApprovalApply apply = findOrThrow(id);
        LoginUser user = requireUser();
        if (!ShareConstants.APPLY_PENDING.equals(apply.getStatus())) {
            throw new BizException(ErrorCode.APPROVAL_STATUS_ILLEGAL, "仅审批中的申请可以撤回");
        }
        boolean owner = apply.getApplicantId() != null && apply.getApplicantId().equals(user.getUserId());
        if (!owner && !user.isAdmin()) {
            throw new BizException(ErrorCode.APPROVAL_NO_PERMISSION, "只有申请人本人可以撤回申请");
        }
        apply.setStatus(ShareConstants.APPLY_WITHDRAWN);
        apply.setCurrentNodeName("已撤回");
        apply.setCurrentNodeRole(null);
        apply.setCurrentNodeAssigneeId(null);
        apply.setCurrentNodeAssigneeName(null);
        applyRepository.save(apply);
        skipPendingNodes(apply.getId(), null);
        notifyService.notifyApplicant(apply, "共享数据申请已撤回",
                "申请单 " + apply.getApplyNo() + "（" + safeName(apply) + "）已撤回。", "INFO");
        log.info("撤回共享申请 applyNo={} by={}", apply.getApplyNo(), user.getUsername());
        return detail(id);
    }

    /** 转办：把当前节点改派给其他用户处理（角色仍需匹配或由管理员操作）。 */
    @Transactional
    public Map<String, Object> transfer(Long id, ApprovalActionRequest req) {
        if (req == null || req.toUserId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "转办必须指定 toUserId");
        }
        ApprovalApply apply = findOrThrow(id);
        requirePending(apply);
        LoginUser user = requireUser();
        ApprovalFlowNode node = currentPendingNode(apply);
        checkApprovePermission(apply, node, user);

        String toName = req.toUserName() == null || req.toUserName().isBlank()
                ? "用户" + req.toUserId() : ShareUtils.truncate(req.toUserName().trim(), 64);
        node.setTransferFromId(user.getUserId());
        node.setTransferFromName(user.getUsername());
        node.setTransferToId(req.toUserId());
        node.setTransferToName(toName);
        node.setAssigneeId(req.toUserId());
        node.setAssigneeName(toName);
        nodeRepository.save(node);

        apply.setCurrentNodeAssigneeId(req.toUserId());
        apply.setCurrentNodeAssigneeName(toName);
        applyRepository.save(apply);

        notifyService.notifyUser(req.toUserId(), "共享数据申请转办待审批",
                "申请单 " + apply.getApplyNo() + "（" + safeName(apply) + "）由 " + user.getUsername()
                        + " 转办给您，节点：" + node.getNodeName(),
                "APPROVAL", apply.getApplyNo(), "WARN", "/approval/todo");
        log.info("转办共享申请 applyNo={} node={} from={} to={}", apply.getApplyNo(), node.getNodeName(),
                user.getUsername(), toName);
        return detail(id);
    }

    /** 续期：对已通过申请延长授权与凭据有效期，并追加一条续期轨迹。 */
    @Transactional
    public Map<String, Object> renew(Long id, ApprovalActionRequest req) {
        ApprovalApply apply = findOrThrow(id);
        LoginUser user = requireUser();
        if (!ShareConstants.APPLY_APPROVED.equals(apply.getStatus())) {
            throw new BizException(ErrorCode.APPROVAL_STATUS_ILLEGAL, "仅已通过的申请可以续期");
        }
        if (req == null || req.newEndDate() == null || req.newEndDate().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "续期必须指定 newEndDate（yyyy-MM-dd）");
        }
        LocalDate newEndDate = ShareUtils.parseDate(req.newEndDate());
        if (newEndDate == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newEndDate 格式不正确，应为 yyyy-MM-dd");
        }
        if (newEndDate.isBefore(LocalDate.now())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "续期后的到期日期不能早于今天");
        }
        if (apply.getEndDate() != null && newEndDate.isBefore(apply.getEndDate())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "续期后的到期日期不能早于原到期日期");
        }
        Optional<ApprovalGrant> grantOpt = apply.getGrantId() == null
                ? Optional.empty()
                : grantRepository.findByIdAndDeletedFalse(apply.getGrantId());
        ApprovalGrant grant = grantOpt.orElseThrow(() ->
                new BizException(ErrorCode.NOT_FOUND, "申请单尚未签发授权，无法续期"));
        if (ShareConstants.GRANT_REVOKED.equals(grant.getStatus())) {
            throw new BizException(ErrorCode.APPROVAL_STATUS_ILLEGAL, "授权已回收，不能续期，请重新提交申请");
        }
        apply.setEndDate(newEndDate);
        applyRepository.save(apply);
        grantService.extend(grant, ShareUtils.endOfDay(newEndDate));

        List<ApprovalFlowNode> nodes = nodeRepository.findByApplyIdOrderByNodeNoAscIdAsc(id);
        int nextNo = nodes.stream().map(ApprovalFlowNode::getNodeNo).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(0) + 1;
        ApprovalFlowNode renewNode = new ApprovalFlowNode();
        renewNode.setApplyId(apply.getId());
        renewNode.setApplyNo(apply.getApplyNo());
        renewNode.setNodeNo(nextNo);
        renewNode.setNodeName("授权续期");
        renewNode.setNodeType(ShareConstants.NODE_TYPE_RENEW);
        renewNode.setRoleCode(user.getRoleCode());
        renewNode.setStatus(ShareConstants.NODE_APPROVED);
        renewNode.setApproverId(user.getUserId());
        renewNode.setApproverName(user.getUsername());
        renewNode.setComment("续期至 " + newEndDate);
        renewNode.setHandleTime(LocalDateTime.now());
        nodeRepository.save(renewNode);

        notifyService.notifyApplicant(apply, "共享授权续期成功",
                "申请单 " + apply.getApplyNo() + "（" + safeName(apply) + "）已续期至 " + newEndDate + "。", "INFO");
        log.info("共享授权续期 applyNo={} newEnd={} operator={}", apply.getApplyNo(), newEndDate,
                user.getUsername());
        return detail(id);
    }

    // ==================== 审批流配置 ====================

    /** 审批流节点配置（普通流 / 涉敏流）。 */
    public Map<String, Object> flowConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sensitiveLevel", sensitiveLevel);
        data.put("description", "涉敏判定：读取 cat_catalog.secret_level，>= share.security.sensitive-level 时"
                + "在普通流后追加安全管理员节点（共 3 级），否则 2 级审批");
        data.put("normalFlow", flowOf(false));
        data.put("sensitiveFlow", flowOf(true));
        List<Map<String, Object>> nodeTypes = new ArrayList<>();
        nodeTypes.add(Map.of("nodeType", ShareConstants.NODE_TYPE_BIZ_ADMIN, "nodeName", "业务管理员审批",
                "roleCode", ShareConstants.ROLE_BIZ_ADMIN, "desc", "审核申请的业务合理性与使用范围"));
        nodeTypes.add(Map.of("nodeType", ShareConstants.NODE_TYPE_PROVIDER, "nodeName", "数据提供方审批",
                "roleCode", ShareConstants.ROLE_PROVIDER, "desc", "数据提供方确认供数能力与数据范围"));
        nodeTypes.add(Map.of("nodeType", ShareConstants.NODE_TYPE_SECURITY, "nodeName", "安全管理员审批",
                "roleCode", ShareConstants.ROLE_SECURITY, "desc", "涉敏数据必经，校验分级与脱敏策略"));
        data.put("nodeTypes", nodeTypes);
        return data;
    }

    private List<Map<String, Object>> flowOf(boolean sensitive) {
        List<NodeDef> defs = nodeDefs(sensitive);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            NodeDef def = defs.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeNo", i + 1);
            item.put("nodeType", def.nodeType());
            item.put("nodeName", def.nodeName());
            item.put("roleCode", def.roleCode());
            nodes.add(item);
        }
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("name", sensitive ? "涉敏数据审批流" : "普通数据审批流");
        flow.put("nodeCount", defs.size());
        flow.put("nodes", nodes);
        return List.of(flow);
    }

    /** 节点定义：业务管理员 → 数据提供方（→ 安全管理员）。 */
    public List<NodeDef> nodeDefs(boolean sensitive) {
        List<NodeDef> defs = new ArrayList<>();
        defs.add(new NodeDef(ShareConstants.NODE_TYPE_BIZ_ADMIN, "业务管理员审批",
                ShareConstants.ROLE_BIZ_ADMIN));
        defs.add(new NodeDef(ShareConstants.NODE_TYPE_PROVIDER, "数据提供方审批",
                ShareConstants.ROLE_PROVIDER));
        if (sensitive) {
            defs.add(new NodeDef(ShareConstants.NODE_TYPE_SECURITY, "安全管理员审批",
                    ShareConstants.ROLE_SECURITY));
        }
        return defs;
    }

    // ==================== 内部实现 ====================

    public ApprovalApply findOrThrow(Long id) {
        return applyRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.APPROVAL_NOT_FOUND));
    }

    private LoginUser requireUser() {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private void requirePending(ApprovalApply apply) {
        if (!ShareConstants.APPLY_PENDING.equals(apply.getStatus())) {
            throw new BizException(ErrorCode.APPROVAL_STATUS_ILLEGAL,
                    "申请单当前状态为 " + apply.getStatus() + "，不可执行该操作");
        }
    }

    /** 当前待审节点（按 currentNodeNo 定位，异常时兜底取最小序号的 PENDING 节点）。 */
    public ApprovalFlowNode currentPendingNode(ApprovalApply apply) {
        if (apply.getCurrentNodeNo() != null) {
            ApprovalFlowNode node = nodeRepository
                    .findByApplyIdAndNodeNo(apply.getId(), apply.getCurrentNodeNo()).orElse(null);
            if (node != null && ShareConstants.NODE_PENDING.equals(node.getStatus())) {
                return node;
            }
        }
        return nodeRepository.findByApplyIdAndStatus(apply.getId(), ShareConstants.NODE_PENDING).stream()
                .min(Comparator.comparing(n -> n.getNodeNo() == null ? Integer.MAX_VALUE : n.getNodeNo()))
                .orElseThrow(() -> new BizException(ErrorCode.APPROVAL_STATUS_ILLEGAL, "当前没有待审批节点"));
    }

    /** 审批权限校验：节点角色匹配，或为节点指定办理人（转办），ADMIN 全放行。 */
    private void checkApprovePermission(ApprovalApply apply, ApprovalFlowNode node, LoginUser user) {
        if (user.isAdmin()) {
            return;
        }
        boolean roleOk = node.getRoleCode() == null
                || node.getRoleCode().equalsIgnoreCase(user.getRoleCode());
        boolean assigneeOk = node.getAssigneeId() != null && node.getAssigneeId().equals(user.getUserId());
        if (!roleOk && !assigneeOk) {
            throw new BizException(ErrorCode.APPROVAL_NO_PERMISSION,
                    "当前节点（" + node.getNodeName() + "）需 " + node.getRoleCode() + " 角色审批");
        }
    }

    /** 详情读取权限：申请人本人、审批角色或管理员。 */
    private void checkReadPermission(ApprovalApply apply, LoginUser user) {
        if (user.isAdmin() || isApproverRole(user)) {
            return;
        }
        if (apply.getApplicantId() != null && apply.getApplicantId().equals(user.getUserId())) {
            return;
        }
        throw new BizException(ErrorCode.APPROVAL_NO_PERMISSION, "无权查看该申请单");
    }

    private boolean isApproverRole(LoginUser user) {
        String role = user.getRoleCode();
        return role != null && (ShareConstants.ROLE_BIZ_ADMIN.equalsIgnoreCase(role)
                || ShareConstants.ROLE_PROVIDER.equalsIgnoreCase(role)
                || ShareConstants.ROLE_SECURITY.equalsIgnoreCase(role));
    }

    private boolean canApprove(ApprovalApply apply, LoginUser user) {
        if (!ShareConstants.APPLY_PENDING.equals(apply.getStatus())) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        String role = user.getRoleCode();
        String currentRole = apply.getCurrentNodeRole();
        boolean roleOk = currentRole != null && currentRole.equalsIgnoreCase(role);
        boolean assigneeOk = apply.getCurrentNodeAssigneeId() != null
                && apply.getCurrentNodeAssigneeId().equals(user.getUserId());
        return roleOk || assigneeOk;
    }

    /** 把其余待审节点置为 SKIPPED（驳回/撤回时）。 */
    private void skipPendingNodes(Long applyId, Long exceptNodeId) {
        for (ApprovalFlowNode node : nodeRepository.findByApplyIdAndStatus(applyId, ShareConstants.NODE_PENDING)) {
            if (exceptNodeId != null && exceptNodeId.equals(node.getId())) {
                continue;
            }
            node.setStatus(ShareConstants.NODE_SKIPPED);
            node.setHandleTime(LocalDateTime.now());
            nodeRepository.save(node);
        }
    }

    private String safeName(ApprovalApply apply) {
        if (apply.getCatalogName() != null) {
            return apply.getCatalogName();
        }
        return apply.getApplyNo();
    }

    private Map<String, Object> toMap(ApprovalApply apply) {
        Map<String, Object> map = JsonUtil.mapper().convertValue(apply,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        return new LinkedHashMap<>(map);
    }

    /** 申请时解析到的共享 API 摘要（避免在事务中持有游离实体）。 */
    private record ShareApiRef(Long id, String apiCode, String apiName, Long catalogId,
                               String catalogName, Integer qpsLimit) {
    }
}
