package com.share.security.service;

import com.share.common.security.UserContext;
import com.share.common.spi.MaskType;
import com.share.common.util.IdGen;
import com.share.security.entity.SecMaskLog;
import com.share.security.repository.SecMaskLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 脱敏日志服务（FR-10）：记录敏感脱敏场景，落库内容不含原文，仅保存脱敏结果样本与原文摘要。
 * <p>日志写入失败不影响脱敏主流程（降级为告警日志）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaskLogService {

    private final SecMaskLogRepository maskLogRepository;

    /**
     * 记录一次脱敏动作。
     *
     * @param ruleCode      规则编码（可空）
     * @param type          算法类型
     * @param targetField   脱敏字段（可空）
     * @param originalValue 原文（仅用于计算摘要，不落库）
     * @param maskedValue   脱敏结果（截断后落库）
     * @param sourceType    来源：TEST / MASK_ENGINE / API
     */
    public void record(String ruleCode, MaskType type, String targetField, String originalValue,
                       String maskedValue, String sourceType, String targetType, Long targetId, long costMs) {
        try {
            SecMaskLog entity = new SecMaskLog();
            entity.setRuleCode(ruleCode);
            entity.setMaskType(type == null ? MaskType.NONE.name() : type.name());
            entity.setTargetField(targetField);
            entity.setMaskedValue(truncate(maskedValue, 500));
            entity.setValueDigest(originalValue == null ? null
                    : IdGen.sha256(originalValue).substring(0, 32));
            entity.setSourceType(sourceType);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setOperator(UserContext.username());
            entity.setMaskTime(LocalDateTime.now());
            entity.setCostMs(costMs);
            maskLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("脱敏日志落库失败: {}", e.getMessage());
        }
    }

    /** 脱敏日志总数（概览用）。 */
    public long count() {
        return maskLogRepository.countByDeletedFalse();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
