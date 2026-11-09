package com.share.share.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.IdGen;
import com.share.share.constant.ShareConstants;
import com.share.share.dto.CredentialCreateRequest;
import com.share.share.entity.ShareApi;
import com.share.share.entity.ShareCredential;
import com.share.share.repository.ShareApiRepository;
import com.share.share.repository.ShareCredentialRepository;
import com.share.share.support.CrossDomainQueryService;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享调用凭据服务（FR-07）。
 * <p>签发时生成 AppKey/AppSecret，库中只保存 AppSecret 的 SHA-256 摘要，明文仅在签发响应中返回一次；
 * 开放调用按 AppKey + AppSecret 校验，吊销/到期后立即失效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareCredentialService {

    private final ShareCredentialRepository credentialRepository;
    private final ShareApiRepository apiRepository;
    private final CrossDomainQueryService crossDomainQueryService;

    /** 签发结果：凭据 + 只返回一次的明文 AppSecret。 */
    public record IssuedCredential(ShareCredential credential, String appSecret) {
    }

    /** 分页查询凭据（appKey 只展示前 8 位，secret 永不回显）。 */
    public PageResult<ShareCredential> list(Long apiId, Long consumerId, String status, String keyword,
                                            int pageNum, int pageSize) {
        Specification<ShareCredential> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            if (apiId != null) {
                ps.add(cb.equal(root.get("apiId"), apiId));
            }
            if (consumerId != null) {
                ps.add(cb.equal(root.get("consumerId"), consumerId));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("appKey")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("consumerName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("apiCode"), "")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        int num = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Page<ShareCredential> page = credentialRepository.findAll(spec,
                PageRequest.of(num - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.of(page.getContent(), page.getTotalElements(), num, size);
    }

    /** 管理端签发凭据。 */
    @Transactional
    public Map<String, Object> issue(CredentialCreateRequest req) {
        ShareApi api = apiRepository.findByIdAndDeletedFalse(req.apiId())
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_API_NOT_FOUND));
        if (ShareConstants.API_DRAFT.equals(api.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "共享服务尚未发布，不能签发凭据");
        }
        int expireDays = req.expireDays() == null || req.expireDays() <= 0 ? 90 : Math.min(req.expireDays(), 3650);
        int grantLevel = req.grantLevel() != null && req.grantLevel() > 0
                ? req.grantLevel() : Math.max(1, crossDomainQueryService.catalogLevel(api.getCatalogId()));
        IssuedCredential issued = doIssue(api, req.consumerId(),
                req.consumerName() == null ? "consumer" : req.consumerName(),
                grantLevel, LocalDateTime.now().plusDays(expireDays), null, null, req.remark());
        Map<String, Object> data = toResponse(issued);
        data.put("expireDays", expireDays);
        return data;
    }

    /** 审批通过后由授权流程签发凭据。 */
    @Transactional
    public IssuedCredential issueForGrant(ShareApi api, Long consumerId, String consumerName,
                                          int grantLevel, LocalDateTime expireTime,
                                          Long grantId, Long applyId) {
        return doIssue(api, consumerId, consumerName, grantLevel, expireTime, grantId, applyId, "审批通过自动签发");
    }

    private IssuedCredential doIssue(ShareApi api, Long consumerId, String consumerName, int grantLevel,
                                     LocalDateTime expireTime, Long grantId, Long applyId, String remark) {
        String appKey = generateAppKey();
        String appSecret = "SK" + IdGen.secret(30);
        ShareCredential credential = new ShareCredential();
        credential.setApiId(api.getId());
        credential.setApiCode(api.getApiCode());
        credential.setApiName(api.getApiName());
        credential.setConsumerId(consumerId);
        credential.setConsumerName(ShareUtils.truncate(consumerName, 128));
        credential.setAppKey(appKey);
        credential.setAppSecretHash(sha256(appSecret));
        credential.setAppSecretPreview(appSecret.substring(0, 4) + "****"
                + appSecret.substring(appSecret.length() - 4));
        credential.setGrantLevel(Math.max(grantLevel, 1));
        credential.setQpsLimit(api.getQpsLimit() == null ? 100 : api.getQpsLimit());
        credential.setStatus(ShareConstants.CRED_ACTIVE);
        credential.setExpireTime(expireTime);
        credential.setCallCount(0L);
        credential.setGrantId(grantId);
        credential.setApplyId(applyId);
        credential.setRemark(ShareUtils.truncate(remark, 255));
        ShareCredential saved = credentialRepository.save(credential);
        log.info("签发共享调用凭据 appKey={} api={} consumer={} level={}", appKey, api.getApiCode(),
                consumerName, grantLevel);
        return new IssuedCredential(saved, appSecret);
    }

    /** 签发响应：扁平字段 + 只返回一次的 appSecret。 */
    public Map<String, Object> toResponse(IssuedCredential issued) {
        ShareCredential c = issued.credential();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", c.getId());
        data.put("apiId", c.getApiId());
        data.put("apiCode", c.getApiCode());
        data.put("apiName", c.getApiName());
        data.put("consumerId", c.getConsumerId());
        data.put("consumerName", c.getConsumerName());
        data.put("appKey", c.getAppKey());
        data.put("appSecret", issued.appSecret());
        data.put("appSecretPreview", c.getAppSecretPreview());
        data.put("grantLevel", c.getGrantLevel());
        data.put("qpsLimit", c.getQpsLimit());
        data.put("status", c.getStatus());
        data.put("expireTime", c.getExpireTime());
        data.put("createTime", c.getCreateTime());
        data.put("secretNotice", "AppSecret 仅在本次响应返回，请妥善保管，服务端不可再查看");
        data.put("callSample", "curl -H \"X-App-Key: " + c.getAppKey()
                + "\" -H \"X-App-Secret: ******\" \"/api/open/v1/" + c.getApiCode() + "?pageNum=1&pageSize=10\"");
        return data;
    }

    /** 吊销凭据。 */
    @Transactional
    public Map<String, Object> revoke(Long id, String reason) {
        ShareCredential credential = credentialRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "凭据不存在"));
        if (ShareConstants.CRED_REVOKED.equals(credential.getStatus())) {
            return toSafeMap(credential);
        }
        credential.setStatus(ShareConstants.CRED_REVOKED);
        credential.setRevokeTime(LocalDateTime.now());
        credential.setRevokeReason(ShareUtils.truncate(reason, 255));
        credentialRepository.save(credential);
        log.info("吊销共享调用凭据 id={} appKey={}", id, credential.getAppKey());
        return toSafeMap(credential);
    }

    /** 按授权回收凭据（到期/授权回收联动）。 */
    @Transactional
    public void revokeByGrant(Long credentialId, String reason) {
        if (credentialId == null) {
            return;
        }
        credentialRepository.findByIdAndDeletedFalse(credentialId).ifPresent(c -> {
            if (!ShareConstants.CRED_REVOKED.equals(c.getStatus())) {
                c.setStatus(ShareConstants.CRED_REVOKED);
                c.setRevokeTime(LocalDateTime.now());
                c.setRevokeReason(ShareUtils.truncate(reason, 255));
                credentialRepository.save(c);
                log.info("授权回收联动吊销凭据 id={} appKey={}", c.getId(), c.getAppKey());
            }
        });
    }

    /** 置为过期（到期回收）。 */
    @Transactional
    public void markExpired(Long credentialId) {        if (credentialId == null) {
            return;
        }
        credentialRepository.findByIdAndDeletedFalse(credentialId).ifPresent(c -> {
            if (ShareConstants.CRED_ACTIVE.equals(c.getStatus())) {
                c.setStatus(ShareConstants.CRED_EXPIRED);
                credentialRepository.save(c);
            }
        });
    }

    /** 续期：延长凭据到期时间并恢复为 ACTIVE（授权续期联动）。 */
    @Transactional
    public void renewExpire(Long credentialId, LocalDateTime newExpireTime) {
        if (credentialId == null || newExpireTime == null) {
            return;
        }
        credentialRepository.findByIdAndDeletedFalse(credentialId).ifPresent(c -> {
            if (ShareConstants.CRED_REVOKED.equals(c.getStatus())) {
                return;
            }
            c.setExpireTime(newExpireTime);
            c.setStatus(ShareConstants.CRED_ACTIVE);
            credentialRepository.save(c);
            log.info("凭据续期 id={} appKey={} newExpire={}", c.getId(), c.getAppKey(), newExpireTime);
        });
    }

    /**
     * 校验 AppKey / AppSecret，返回有效凭据；任何异常统一抛 SHARE_CREDENTIAL_INVALID。
     * <p>过期时顺带把状态置为 EXPIRED（noRollbackFor 保证状态更新不被异常回滚）。</p>
     */
    @Transactional(noRollbackFor = BizException.class)
    public ShareCredential authenticate(String appKey, String appSecret) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "缺少请求头 X-App-Key / X-App-Secret");
        }
        ShareCredential credential = credentialRepository.findByAppKeyAndDeletedFalse(appKey.trim())
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "AppKey 不存在"));
        if (ShareConstants.CRED_REVOKED.equals(credential.getStatus())) {
            throw new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "凭据已被吊销");
        }
        if (credential.getExpireTime() != null && credential.getExpireTime().isBefore(LocalDateTime.now())) {
            if (!ShareConstants.CRED_EXPIRED.equals(credential.getStatus())) {
                credential.setStatus(ShareConstants.CRED_EXPIRED);
                credentialRepository.save(credential);
            }
            throw new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "凭据已过期");
        }
        if (!ShareConstants.CRED_ACTIVE.equals(credential.getStatus())) {
            throw new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "凭据状态不可用: " + credential.getStatus());
        }
        String expected = credential.getAppSecretHash();
        boolean matched = expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sha256(appSecret).getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            throw new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "AppSecret 校验失败");
        }
        return credential;
    }

    /** 刷新凭据调用计量。 */
    @Transactional
    public void touchCall(ShareCredential credential) {
        credential.setCallCount((credential.getCallCount() == null ? 0L : credential.getCallCount()) + 1);
        credential.setLastCallTime(LocalDateTime.now());
        credentialRepository.save(credential);
    }

    public ShareCredential findOrThrow(Long id) {
        return credentialRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_CREDENTIAL_INVALID, "凭据不存在"));
    }

    /** 对外安全视图（不含任何摘要信息）。 */
    public Map<String, Object> toSafeMap(ShareCredential c) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", c.getId());
        data.put("apiId", c.getApiId());
        data.put("apiCode", c.getApiCode());
        data.put("apiName", c.getApiName());
        data.put("consumerId", c.getConsumerId());
        data.put("consumerName", c.getConsumerName());
        data.put("appKey", c.getAppKey());
        data.put("appSecretPreview", c.getAppSecretPreview());
        data.put("grantLevel", c.getGrantLevel());
        data.put("qpsLimit", c.getQpsLimit());
        data.put("status", c.getStatus());
        data.put("expireTime", c.getExpireTime());
        data.put("revokeTime", c.getRevokeTime());
        data.put("revokeReason", c.getRevokeReason());
        data.put("lastCallTime", c.getLastCallTime());
        data.put("callCount", c.getCallCount());
        data.put("applyId", c.getApplyId());
        data.put("grantId", c.getGrantId());
        data.put("createTime", c.getCreateTime());
        return data;
    }

    private String generateAppKey() {
        for (int i = 0; i < 5; i++) {
            String appKey = "AK" + IdGen.uuid().substring(0, 18).toUpperCase();
            if (credentialRepository.findByAppKeyAndDeletedFalse(appKey).isEmpty()) {
                return appKey;
            }
        }
        throw new BizException(ErrorCode.INTERNAL, "AppKey 生成失败，请重试");
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败", e);
        }
    }
}
