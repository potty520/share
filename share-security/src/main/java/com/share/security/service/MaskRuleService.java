package com.share.security.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.spi.MaskType;
import com.share.security.dto.SecurityRequests;
import com.share.security.dto.SecurityVOs;
import com.share.security.entity.SecMaskRule;
import com.share.security.repository.SecMaskRuleRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 脱敏规则服务（FR-10）：规则 CRUD 与试算，试算结果同时落 sec_mask_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaskRuleService {

    private final SecMaskRuleRepository ruleRepository;
    private final SecurityMaskService maskService;
    private final MaskLogService maskLogService;

    /** 分页查询规则：关键字（编码/名称/字段）、算法、启用状态。 */
    public SecurityVOs.PageResult<SecMaskRule> list(String keyword, String maskType, Boolean enabled,
                                                    int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Specification<SecMaskRule> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("deleted"), false));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("ruleCode")), like),
                        cb.like(cb.lower(root.<String>get("ruleName")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("targetField"), "")), like)
                ));
            }
            if (maskType != null && !maskType.isBlank()) {
                predicates.add(cb.equal(root.get("maskType"), maskType.trim().toUpperCase(Locale.ROOT)));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<SecMaskRule> result = ruleRepository.findAll(spec,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return new SecurityVOs.PageResult<>(result.getContent(), result.getTotalElements());
    }

    public SecMaskRule get(Long id) {
        return requireRule(id);
    }

    /** 新增规则。 */
    @Transactional
    public SecMaskRule create(SecurityRequests.MaskRuleSaveRequest request) {
        if (request.ruleCode() == null || request.ruleCode().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则编码不能为空");
        }
        String ruleCode = request.ruleCode().trim().toUpperCase(Locale.ROOT);
        if (ruleRepository.existsByRuleCode(ruleCode)) {
            throw new BizException(ErrorCode.CONFLICT, "规则编码已存在: " + ruleCode);
        }
        if (request.ruleName() == null || request.ruleName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则名称不能为空");
        }
        MaskType type = validateMaskType(request.maskType());
        SecMaskRule rule = new SecMaskRule();
        rule.setRuleCode(ruleCode);
        rule.setRuleName(request.ruleName().trim());
        rule.setMaskType(type.name());
        rule.setParam(request.param());
        rule.setTargetField(request.targetField());
        rule.setMatchPattern(request.matchPattern());
        rule.setLevelMin(validateLevel(request.levelMin(), 1));
        rule.setEnabled(request.enabled() == null || request.enabled());
        rule.setDescription(request.description());
        SecMaskRule saved = ruleRepository.save(rule);
        log.info("新增脱敏规则: code={}, type={}", ruleCode, type);
        return saved;
    }

    /** 修改规则（规则编码不可变更，避免引用失效）。 */
    @Transactional
    public SecMaskRule update(Long id, SecurityRequests.MaskRuleSaveRequest request) {
        SecMaskRule rule = requireRule(id);
        if (request.ruleCode() != null && !request.ruleCode().isBlank()
                && !request.ruleCode().trim().equalsIgnoreCase(rule.getRuleCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则编码创建后不可修改");
        }
        if (request.ruleName() != null && !request.ruleName().isBlank()) {
            rule.setRuleName(request.ruleName().trim());
        }
        if (request.maskType() != null && !request.maskType().isBlank()) {
            rule.setMaskType(validateMaskType(request.maskType()).name());
        }
        if (request.param() != null) {
            rule.setParam(request.param());
        }
        if (request.targetField() != null) {
            rule.setTargetField(request.targetField());
        }
        if (request.matchPattern() != null) {
            rule.setMatchPattern(request.matchPattern());
        }
        if (request.levelMin() != null) {
            rule.setLevelMin(validateLevel(request.levelMin(), 1));
        }
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        if (request.description() != null) {
            rule.setDescription(request.description());
        }
        SecMaskRule saved = ruleRepository.save(rule);
        log.info("修改脱敏规则: id={}, code={}", id, rule.getRuleCode());
        return saved;
    }

    /** 逻辑删除规则。 */
    @Transactional
    public void delete(Long id) {
        SecMaskRule rule = requireRule(id);
        rule.setDeleted(true);
        rule.setEnabled(false);
        ruleRepository.save(rule);
        log.info("删除脱敏规则: id={}, code={}", id, rule.getRuleCode());
    }

    /**
     * 规则试算：入参可直接给算法/参数，也可只给 ruleCode 复用规则默认值。
     * <p>结果为脱敏后的值，同时记录脱敏日志。</p>
     */
    public SecurityVOs.MaskTestResult test(SecurityRequests.MaskTestRequest request) {
        if (request.value() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "value 不能为空");
        }
        SecMaskRule rule = null;
        if (request.ruleCode() != null && !request.ruleCode().isBlank()) {
            rule = ruleRepository.findByRuleCodeAndDeletedFalse(request.ruleCode().trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new BizException(ErrorCode.SEC_MASK_RULE_NOT_FOUND));
        }
        String rawType = request.maskType() != null && !request.maskType().isBlank()
                ? request.maskType()
                : (rule == null ? null : rule.getMaskType());
        if (rawType == null || rawType.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "maskType 不能为空");
        }
        MaskType type = validateMaskType(rawType);
        String param = request.param() != null ? request.param() : (rule == null ? null : rule.getParam());
        long start = System.currentTimeMillis();
        String masked = maskService.mask(type, request.value(), param);
        long cost = System.currentTimeMillis() - start;
        maskLogService.record(rule == null ? null : rule.getRuleCode(), type,
                rule == null ? null : rule.getTargetField(), request.value(), masked, "TEST",
                "MASK_RULE", rule == null ? null : rule.getId(), cost);
        return new SecurityVOs.MaskTestResult(request.value(), masked, type.name(), param,
                rule == null ? null : rule.getRuleCode(), cost);
    }

    private SecMaskRule requireRule(Long id) {
        return ruleRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.SEC_MASK_RULE_NOT_FOUND));
    }

    /** 算法类型校验：必须是 MASK/REPLACE/HASH/GENERALIZE/PERTURB/ENCRYPT 之一。 */
    private MaskType validateMaskType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "脱敏算法不能为空");
        }
        MaskType type = SecurityMaskService.parseType(raw);
        if (type == MaskType.NONE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的脱敏算法: " + raw);
        }
        return type;
    }

    private Integer validateLevel(Integer level, int defaultLevel) {
        if (level == null) {
            return defaultLevel;
        }
        if (level < 1 || level > 4) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密级取值范围为 1~4");
        }
        return level;
    }
}
