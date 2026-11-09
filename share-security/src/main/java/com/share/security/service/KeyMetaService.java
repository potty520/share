package com.share.security.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.IdGen;
import com.share.security.dto.SecurityRequests;
import com.share.security.entity.SecKeyMeta;
import com.share.security.repository.SecKeyMetaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 密钥元数据服务（FR-10）：只管理别 名/算法/状态/版本/轮换时间，不保存明文密钥。
 * <p>密钥材料由 KMS 或配置文件托管，轮换时仅更新元数据与指纹。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeyMetaService {

    private final SecKeyMetaRepository keyMetaRepository;

    /** 密钥列表（按 id 升序）。 */
    public List<SecKeyMeta> list() {
        return keyMetaRepository.findByDeletedFalseOrderByIdAsc();
    }

    /** 新增密钥元数据。 */
    @Transactional
    public SecKeyMeta create(SecurityRequests.KeyCreateRequest request) {
        if (request.keyAlias() == null || request.keyAlias().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyAlias 不能为空");
        }
        String alias = request.keyAlias().trim();
        if (keyMetaRepository.existsByKeyAlias(alias)) {
            throw new BizException(ErrorCode.CONFLICT, "密钥别名已存在: " + alias);
        }
        String algorithm = (request.algorithm() == null || request.algorithm().isBlank())
                ? "AES-256-GCM" : request.algorithm().trim().toUpperCase(Locale.ROOT);
        int rotateDays = request.rotateDays() == null ? 90 : request.rotateDays();
        if (rotateDays < 1 || rotateDays > 3650) {
            throw new BizException(ErrorCode.BAD_REQUEST, "轮换周期取值范围 1~3650 天");
        }
        SecKeyMeta meta = new SecKeyMeta();
        meta.setKeyAlias(alias);
        meta.setAlgorithm(algorithm);
        meta.setKeyStatus("ACTIVE");
        meta.setKeyVersion(1);
        meta.setKeyFingerprint(newFingerprint());
        meta.setLastRotatedTime(LocalDateTime.now());
        meta.setNextRotateTime(LocalDateTime.now().plusDays(rotateDays));
        meta.setRotateDays(rotateDays);
        meta.setDescription(request.description());
        SecKeyMeta saved = keyMetaRepository.save(meta);
        log.info("新增密钥元数据: alias={}, algorithm={}", alias, algorithm);
        return saved;
    }

    /** 轮换密钥：版本 +1、更新指纹与轮换时间，返回最新元数据（不含明文）。 */
    @Transactional
    public SecKeyMeta rotate(Long id) {
        SecKeyMeta meta = keyMetaRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "密钥元数据不存在: " + id));
        LocalDateTime now = LocalDateTime.now();
        int rotateDays = meta.getRotateDays() == null ? 90 : meta.getRotateDays();
        meta.setKeyVersion(meta.getKeyVersion() == null ? 2 : meta.getKeyVersion() + 1);
        meta.setKeyStatus("ACTIVE");
        meta.setKeyFingerprint(newFingerprint());
        meta.setLastRotatedTime(now);
        meta.setNextRotateTime(now.plusDays(rotateDays));
        SecKeyMeta saved = keyMetaRepository.save(meta);
        log.info("密钥轮换完成: alias={}, version={}", meta.getKeyAlias(), meta.getKeyVersion());
        return saved;
    }

    /** 生成随机指纹（非密钥本体，仅用于比对与审计）。 */
    private String newFingerprint() {
        return IdGen.sha256(IdGen.uuid()).substring(0, 32);
    }
}
