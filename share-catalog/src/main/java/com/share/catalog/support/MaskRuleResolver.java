package com.share.catalog.support;

import com.share.catalog.entity.CatalogItem;
import com.share.common.spi.MaskType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 脱敏规则解析（FR-10）：把编目上的 {@code maskRuleCode} / 字段名映射为脱敏算法与参数。
 * <p>share-security 维护规则库，这里只做本地映射保证预览链路可独立工作；
 * 实际脱敏计算统一调用 {@link com.share.common.spi.MaskService}（share-security 提供实现）。</p>
 */
@Component
public class MaskRuleResolver {

    /** 解析算法类型。 */
    public MaskType typeOf(CatalogItem item) {
        return typeOf(item.getMaskRuleCode(), item.getFieldName(), item.getFieldComment());
    }

    /** 解析算法参数。 */
    public String paramOf(CatalogItem item) {
        return paramOf(typeOf(item), item.getMaskRuleCode());
    }

    public MaskType typeOf(String ruleCode, String fieldName, String fieldComment) {
        String rule = upper(ruleCode);
        String text = upper(fieldName) + "|" + upper(fieldComment);
        if (rule.equals("MASK") || rule.equals("REPLACE") || rule.equals("HASH")
                || rule.equals("GENERALIZE") || rule.equals("PERTURB") || rule.equals("ENCRYPT")
                || rule.equals("NONE")) {
            return MaskType.valueOf(rule);
        }
        if (rule.contains("HASH")) {
            return MaskType.HASH;
        }
        if (rule.contains("ENCRYPT")) {
            return MaskType.ENCRYPT;
        }
        if (rule.contains("PERTURB")) {
            return MaskType.PERTURB;
        }
        if (rule.contains("IDCARD") || rule.contains("ID_CARD") || rule.contains("CERT")
                || text.contains("身份证") || text.contains("证件")) {
            return MaskType.MASK;
        }
        if (rule.contains("PHONE") || rule.contains("MOBILE")
                || text.contains("手机") || text.contains("电话")) {
            return MaskType.MASK;
        }
        if (rule.contains("BANK") || text.contains("银行卡") || text.contains("银行账号")) {
            return MaskType.MASK;
        }
        if (rule.contains("NAME") || text.contains("姓名")) {
            return MaskType.REPLACE;
        }
        if (rule.contains("ADDR") || text.contains("地址") || text.contains("住址")) {
            return MaskType.GENERALIZE;
        }
        if (rule.contains("EMAIL") || text.contains("邮箱")) {
            return MaskType.MASK;
        }
        // 未识别规则默认遮蔽，保证敏感字段一定被处理
        return MaskType.MASK;
    }

    public String paramOf(MaskType type, String ruleCode) {
        String rule = upper(ruleCode);
        switch (type) {
            case MASK:
                if (rule.contains("IDCARD") || rule.contains("ID_CARD") || rule.contains("CERT")) {
                    return "6,4";
                }
                if (rule.contains("PHONE") || rule.contains("MOBILE")) {
                    return "3,4";
                }
                if (rule.contains("BANK")) {
                    return "4,4";
                }
                if (rule.contains("EMAIL")) {
                    return "2,4";
                }
                return "3,4";
            case REPLACE:
                return "***";
            case GENERALIZE:
                return "6";
            case PERTURB:
                return "1";
            default:
                return null;
        }
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
