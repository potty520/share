package com.share.share.service;

import com.share.common.spi.MaskService;
import com.share.common.spi.MaskType;
import com.share.common.util.IdGen;
import com.share.share.support.CrossDomainQueryService;
import com.share.share.support.CrossDomainQueryService.MaskRuleRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 动态脱敏执行器（FR-07 / FR-10）。
 * <p>优先使用 share-security 提供的 {@link MaskService} 实现（@Primary），规则取自
 * sec_mask_rule（target_field 精确匹配 + match_pattern 泛化匹配）；安全域尚未装配
 * （{@code supports()==false}）或规则算例异常时使用内置算法兜底，保证开放调用结果一定完成脱敏。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaskingHelper {

    private final MaskService maskService;
    private final CrossDomainQueryService crossDomainQueryService;

    /** 规则集：按字段名精确索引 + 按 match_pattern 泛化匹配。 */
    public record RuleSet(Map<String, MaskRuleRef> exact, List<MaskRuleRef> patterns) {
    }

    /**
     * 是否需要对本次调用强制脱敏：凭据授权密级低于数据密级时必须脱敏（动态脱敏核心规则），
     * 管理端在线调试始终脱敏。
     */
    public boolean shouldMask(Integer grantLevel, int requiredLevel, boolean force) {
        if (force) {
            return true;
        }
        if (grantLevel == null) {
            return true;
        }
        return grantLevel < Math.max(requiredLevel, 1);
    }

    /**
     * 对一行数据中声明的脱敏字段做脱敏。
     *
     * @param row        输出行（key 为返回字段名）
     * @param maskFields 脱敏字段列表
     * @return 实际脱敏的字段名
     */
    public List<String> maskRow(Map<String, Object> row, List<String> maskFields) {
        List<String> masked = new ArrayList<>();
        if (row == null || maskFields == null || maskFields.isEmpty()) {
            return masked;
        }
        RuleSet rules = loadRules();
        for (String field : maskFields) {
            String key = findKey(row, field);
            if (key == null) {
                continue;
            }
            Object raw = row.get(key);
            row.put(key, maskValue(field, raw, rules));
            masked.add(key);
        }
        return masked;
    }

    /** 对单个值脱敏（精确规则 → 泛化规则 → 字段语义默认算法）。 */
    public String maskValue(String field, Object raw, RuleSet rules) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        MaskRuleRef rule = resolve(field, rules);
        MaskType type = rule == null || rule.maskType() == null
                ? defaultType(field) : parseType(rule.maskType());
        String param = rule == null ? null : rule.param();
        if (param == null || param.isBlank()) {
            param = defaultParam(field, type);
        }
        try {
            if (maskService.supports(type)) {
                return maskService.mask(type, value, param);
            }
            return localMask(type, value, param);
        } catch (Exception e) {
            log.debug("脱敏算法执行失败，降级为遮蔽: field={} type={} err={}", field, type, e.getMessage());
            return localMask(MaskType.MASK, value, "3,4");
        }
    }

    /** 读取启用中的规则并建立索引。 */
    public RuleSet loadRules() {
        Map<String, MaskRuleRef> exact = new LinkedHashMap<>();
        List<MaskRuleRef> patterns = new ArrayList<>();
        for (MaskRuleRef rule : crossDomainQueryService.maskRules()) {
            if (!rule.enabled()) {
                continue;
            }
            if (rule.field() != null && !rule.field().isBlank()) {
                exact.put(normalize(rule.field()), rule);
            }
            if (rule.matchPattern() != null && !rule.matchPattern().isBlank()) {
                patterns.add(rule);
            }
        }
        return new RuleSet(exact, patterns);
    }

    /** 规则解析：先精确匹配 target_field，再按 match_pattern 正则泛化匹配。 */
    private MaskRuleRef resolve(String field, RuleSet rules) {
        String normalized = normalize(field);
        MaskRuleRef hit = rules.exact().get(normalized);
        if (hit != null) {
            return hit;
        }
        for (MaskRuleRef pattern : rules.patterns()) {
            try {
                if (normalized.matches(pattern.matchPattern().toLowerCase(Locale.ROOT))) {
                    return pattern;
                }
            } catch (Exception ignore) {
                // 非法的正则表达式忽略，继续下一条
            }
        }
        return null;
    }

    // ==================== 内置兜底算法 ====================

    private String localMask(MaskType type, String value, String param) {
        if (value == null || value.isEmpty() || type == null || type == MaskType.NONE) {
            return value;
        }
        return switch (type) {
            case MASK -> localMaskKeep(value, param);
            case REPLACE -> param == null || param.isBlank() ? "***" : param;
            case HASH -> IdGen.sha256(value).substring(0, 16);
            case GENERALIZE -> {
                int keep = parseInt(param, 1);
                yield value.length() <= keep ? "*".repeat(value.length()) : value.substring(0, keep) + "***";
            }
            case PERTURB, ENCRYPT -> IdGen.md5(value).substring(0, 8);
            case NONE -> value;
        };
    }

    /** 遮蔽：保留前 keepLeft 位与后 keepRight 位，如 138****1234。 */
    private String localMaskKeep(String value, String param) {
        int keepLeft = 3;
        int keepRight = 4;
        if (param != null && param.contains(",")) {
            String[] parts = param.split(",");
            keepLeft = parseInt(parts[0], 3);
            keepRight = parseInt(parts[1], 4);
        }
        int len = value.length();
        if (len <= keepLeft + keepRight) {
            // 值过短时整体遮蔽，避免泄露
            return "*".repeat(Math.max(len, 1));
        }
        return value.substring(0, keepLeft) + "*".repeat(len - keepLeft - keepRight) + value.substring(len - keepRight);
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private MaskType parseType(String type) {
        if (type == null) {
            return MaskType.MASK;
        }
        try {
            return MaskType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return MaskType.MASK;
        }
    }

    /** 字段名 → 默认算法（无规则配置时按字段语义推断）。 */
    private MaskType defaultType(String field) {
        String n = normalize(field);
        if (n.contains("name")) {
            return MaskType.REPLACE;
        }
        return MaskType.MASK;
    }

    private String defaultParam(String field, MaskType type) {
        String n = normalize(field);
        if (n.contains("idcard") || n.contains("cert") || n.contains("creditcode") || n.contains("bankcard")) {
            return "6,4";
        }
        if (n.contains("phone") || n.contains("mobile") || n.contains("tel")) {
            return "3,4";
        }
        if (n.contains("email")) {
            return "2,0";
        }
        if (n.contains("addr")) {
            return "4,2";
        }
        if (type == MaskType.REPLACE) {
            return "***";
        }
        return "3,4";
    }

    private String findKey(Map<String, Object> row, String field) {
        String target = normalize(field);
        for (String key : row.keySet()) {
            if (normalize(key).equals(target)) {
                return key;
            }
        }
        return null;
    }

    private String normalize(String key) {
        return key == null ? "" : key.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
