package com.share.share.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.IdGen;
import com.share.share.constant.ShareConstants;
import com.share.share.entity.ApprovalApply;
import com.share.share.entity.ApprovalGrant;
import com.share.share.entity.ShareApi;
import com.share.share.entity.ShareCredential;
import com.share.share.repository.ApprovalApplyRepository;
import com.share.share.repository.ApprovalGrantRepository;
import com.share.share.repository.ShareApiRepository;
import com.share.share.support.PageResult;
import com.share.share.support.ShareUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享授权服务（FR-08）。
 * <p>审批通过自动发放授权（含到期时间）与凭据；到期回收、T-7 到期提醒、授权状态查询与人工回收。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantService {

    /** 到期提醒提前天数（T-7）。 */
    private static final int REMIND_DAYS = 7;

    private final ApprovalGrantRepository grantRepository;
    private final ApprovalApplyRepository applyRepository;
    private final ShareApiRepository apiRepository;
    private final ShareCredentialService credentialService;
    private final ApprovalNotifyService notifyService;

    /** 授权签发结果。 */
    public record GrantIssueResult(ApprovalGrant grant, ShareCredentialService.IssuedCredential credential) {
    }

    /** 分页查询授权（含剩余天数与生效状态）。 */
    public PageResult<Map<String, Object>> list(String status, Long consumerId, Long apiId, String keyword,
                                                Boolean expiring, int pageNum, int pageSize) {
        Specification<ApprovalGrant> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            if (consumerId != null) {
                ps.add(cb.equal(root.get("consumerId"), consumerId));
            }
            if (apiId != null) {
                ps.add(cb.equal(root.get("apiId"), apiId));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("grantNo")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("catalogName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("consumerName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("apiCode"), "")), like)));
            }
            if (Boolean.TRUE.equals(expiring)) {
                ps.add(cb.equal(root.get("status"), ShareConstants.GRANT_ACTIVE));
                ps.add(cb.lessThanOrEqualTo(root.get("endTime"), LocalDateTime.now().plusDays(REMIND_DAYS)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        int num = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Page<ApprovalGrant> page = grantRepository.findAll(spec,
                PageRequest.of(num - 1, size, Sort.by(Sort.Direction.ASC, "endTime")));
        List<Map<String, Object>> views = page.getContent().stream().map(this::toView).toList();
        return PageResult.of(views, page.getTotalElements(), num, size);
    }

    public ApprovalGrant findOrThrow(Long id) {
        return grantRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "授权记录不存在"));
    }

    /** 授权详情。 */
    public Map<String, Object> detail(Long id) {
        return toView(findOrThrow(id));
    }

    /** 审批通过后签发授权 + 凭据。 */
    @Transactional
    public GrantIssueResult issueForApply(ApprovalApply apply) {
        int level = apply.getLevel() == null ? 1 : apply.getLevel();
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = apply.getEndDate() == null
                ? start.plusDays(90) : ShareUtils.endOfDay(apply.getEndDate());
        if (end == null || end.isBefore(start)) {
            end = start.plusDays(90);
        }
        // 解析目标共享 API：优先申请单指定的 API，否则取该目录下已上线的 API
        ShareApi api = resolveApi(apply);

        ApprovalGrant grant = new ApprovalGrant();
        grant.setGrantNo(IdGen.credentialNo());
        grant.setApplyId(apply.getId());
        grant.setApplyNo(apply.getApplyNo());
        grant.setCatalogId(apply.getCatalogId());
        grant.setCatalogName(ShareUtils.truncate(apply.getCatalogName(), 128));
        grant.setConsumerId(apply.getApplicantId());
        grant.setConsumerName(ShareUtils.truncate(apply.getApplicantName(), 64));
        grant.setConsumerDeptId(apply.getApplicantDeptId());
        grant.setLevel(level);
        grant.setStartTime(start);
        grant.setEndTime(end);
        grant.setStatus(ShareConstants.GRANT_ACTIVE);
        grant.setQpsLimit(api == null || api.getQpsLimit() == null ? 100 : api.getQpsLimit());
        if (api != null) {
            grant.setApiId(api.getId());
            grant.setApiCode(api.getApiCode());
        }
        grant = grantRepository.save(grant);

        // 自动签发凭据（AppSecret 明文仅在通知/签发响应中出现一次）
        ShareCredentialService.IssuedCredential issued = null;
        if (api != null) {
            issued = credentialService.issueForGrant(api, apply.getApplicantId(), apply.getApplicantName(),
                    level, end, grant.getId(), apply.getId());
            grant.setCredentialId(issued.credential().getId());
            grant = grantRepository.save(grant);
            apply.setApiId(api.getId());
            apply.setApiCode(api.getApiCode());
            apply.setApiName(api.getApiName());
            apply.setCredentialId(issued.credential().getId());
        }
        apply.setGrantId(grant.getId());
        applyRepository.save(apply);

        String content = "您的共享数据申请 " + apply.getApplyNo() + " 已审批通过，授权编号 " + grant.getGrantNo()
                + "，有效期至 " + end.toLocalDate()
                + (issued == null ? "（未绑定共享服务，暂未签发调用凭据）"
                : "，调用凭据 AppKey：" + issued.credential().getAppKey());
        notifyService.notifyApplicant(apply, "共享数据申请审批通过", content, "INFO");
        log.info("签发共享授权 grantNo={} applyNo={} api={} end={}", grant.getGrantNo(),
                apply.getApplyNo(), grant.getApiCode(), end);
        return new GrantIssueResult(grant, issued);
    }

    /** 人工回收授权（联动吊销凭据）。 */
    @Transactional
    public Map<String, Object> revoke(Long id, String reason) {
        ApprovalGrant grant = findOrThrow(id);
        if (ShareConstants.GRANT_REVOKED.equals(grant.getStatus())) {
            return toView(grant);
        }
        String why = ShareUtils.truncate(reason == null || reason.isBlank() ? "管理员回收授权" : reason, 255);
        grant.setStatus(ShareConstants.GRANT_REVOKED);
        grant.setRevokeTime(LocalDateTime.now());
        grant.setRevokeReason(why);
        grantRepository.save(grant);
        credentialService.revokeByGrant(grant.getCredentialId(), "授权回收：" + why);
        notifyService.notifyGrant(grant, "共享授权已回收",
                "授权 " + grant.getGrantNo() + "（" + safeName(grant) + "）已被回收，原因：" + why, "WARN");
        log.info("回收共享授权 grantNo={} reason={}", grant.getGrantNo(), why);
        return toView(grant);
    }

    /**
     * 扫描到期授权：到期前 T-7 发提醒，已到期自动置 EXPIRED 并吊销凭据。
     *
     * @return 扫描结果统计
     */
    @Transactional
    public Map<String, Object> scanExpiringGrants() {
        LocalDateTime now = LocalDateTime.now();
        List<ApprovalGrant> active = grantRepository.findByStatusAndDeletedFalseOrderByEndTimeAsc(
                ShareConstants.GRANT_ACTIVE);
        int expired = 0;
        int reminded = 0;
        int skipped = 0;
        for (ApprovalGrant grant : active) {
            if (grant.getEndTime() == null) {
                skipped++;
                continue;
            }
            if (grant.getEndTime().isBefore(now)) {
                // 已到期：置 EXPIRED + 吊销凭据 + 通知
                grant.setStatus(ShareConstants.GRANT_EXPIRED);
                grant.setRevokeTime(now);
                grant.setRevokeReason("授权到期自动回收");
                grantRepository.save(grant);
                credentialService.revokeByGrant(grant.getCredentialId(), "授权到期自动回收");
                notifyService.notifyGrant(grant, "共享授权已到期",
                        "授权 " + grant.getGrantNo() + "（" + safeName(grant) + "）已于 "
                                + grant.getEndTime().toLocalDate() + " 到期，调用凭据已停用，如需继续使用请提交续期申请。",
                        "WARN");
                expired++;
            } else if (grant.getEndTime().isBefore(now.plusDays(REMIND_DAYS)) && grant.getRemindTime() == null) {
                // T-7 提醒（同一授权只提醒一次）
                grant.setRemindTime(now);
                grantRepository.save(grant);
                notifyService.notifyGrant(grant, "共享授权即将到期",
                        "授权 " + grant.getGrantNo() + "（" + safeName(grant) + "）将于 "
                                + grant.getEndTime().toLocalDate() + " 到期，请及时提交续期申请。",
                        "INFO");
                reminded++;
            } else {
                skipped++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", active.size());
        result.put("expired", expired);
        result.put("reminded", reminded);
        result.put("skipped", skipped);
        result.put("scanTime", now);
        log.info("授权到期扫描完成：{}", result);
        return result;
    }

    /** 续期：延长授权与凭据到期时间（由申请单续期动作调用）。 */
    @Transactional
    public void extend(ApprovalGrant grant, LocalDateTime newEndTime) {
        grant.setEndTime(newEndTime);
        grant.setStatus(ShareConstants.GRANT_ACTIVE);
        grant.setRemindTime(null);
        grantRepository.save(grant);
        credentialService.renewExpire(grant.getCredentialId(), newEndTime);
        notifyService.notifyGrant(grant, "共享授权已续期",
                "授权 " + grant.getGrantNo() + "（" + safeName(grant) + "）已续期至 "
                        + newEndTime.toLocalDate() + "。", "INFO");
        log.info("共享授权续期 grantNo={} newEnd={}", grant.getGrantNo(), newEndTime);
    }

    /** 对外视图：实体字段 + 剩余天数 + 生效状态 + 凭据摘要。 */
    public Map<String, Object> toView(ApprovalGrant grant) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", grant.getId());
        data.put("grantNo", grant.getGrantNo());
        data.put("applyId", grant.getApplyId());
        data.put("applyNo", grant.getApplyNo());
        data.put("apiId", grant.getApiId());
        data.put("apiCode", grant.getApiCode());
        data.put("catalogId", grant.getCatalogId());
        data.put("catalogName", grant.getCatalogName());
        data.put("consumerId", grant.getConsumerId());
        data.put("consumerName", grant.getConsumerName());
        data.put("consumerDeptId", grant.getConsumerDeptId());
        data.put("level", grant.getLevel());
        data.put("startTime", grant.getStartTime());
        data.put("endTime", grant.getEndTime());
        data.put("status", grant.getStatus());
        data.put("revokeTime", grant.getRevokeTime());
        data.put("revokeReason", grant.getRevokeReason());
        data.put("remindTime", grant.getRemindTime());
        data.put("qpsLimit", grant.getQpsLimit());
        data.put("credentialId", grant.getCredentialId());
        data.put("createTime", grant.getCreateTime());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = grant.getEndTime();
        long remainDays = end == null ? 0 : Duration.between(now, end).toDays();
        data.put("remainDays", Math.max(remainDays, 0));
        String effective = grant.getStatus();
        if (ShareConstants.GRANT_ACTIVE.equals(effective) && end != null && end.isBefore(now)) {
            effective = ShareConstants.GRANT_EXPIRED;
        }
        data.put("effectiveStatus", effective);
        data.put("expiringSoon", ShareConstants.GRANT_ACTIVE.equals(effective) && end != null
                && end.isBefore(now.plusDays(REMIND_DAYS)));
        if (grant.getCredentialId() != null) {
            try {
                data.put("credential", credentialService.toSafeMap(
                        credentialService.findOrThrow(grant.getCredentialId())));
            } catch (Exception ignore) {
                data.put("credential", null);
            }
        }
        return data;
    }

    /** 申请单对应的共享 API：优先申请指定的 API，否则取目录下已上线 API。 */
    private ShareApi resolveApi(ApprovalApply apply) {
        if (apply.getApiId() != null) {
            return apiRepository.findByIdAndDeletedFalse(apply.getApiId()).orElse(null);
        }
        if (apply.getCatalogId() == null) {
            return null;
        }
        List<ShareApi> apis = apiRepository.findByCatalogIdAndDeletedFalse(apply.getCatalogId());
        return apis.stream()
                .filter(a -> ShareConstants.API_ONLINE.equals(a.getStatus()))
                .findFirst()
                .orElse(apis.isEmpty() ? null : apis.get(0));
    }

    private String safeName(ApprovalGrant grant) {
        if (grant.getCatalogName() != null) {
            return grant.getCatalogName();
        }
        return grant.getApiCode() == null ? "共享资源" : grant.getApiCode();
    }
}
