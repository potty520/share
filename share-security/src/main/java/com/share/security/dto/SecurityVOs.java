package com.share.security.dto;

import java.util.List;

/** share-security 返回视图。 */
public final class SecurityVOs {

    private SecurityVOs() {
    }

    /** 通用分页结果。 */
    public record PageResult<T>(List<T> list, long total) {
    }

    /** 规则试算结果。 */
    public record MaskTestResult(String original,
                                 String masked,
                                 String maskType,
                                 String param,
                                 String ruleCode,
                                 long costMs) {
    }

    /** 安全域概览：分级分布 / 规则数 / 敏感资源数。 */
    public record Overview(long templateCount,
                           long tagCount,
                           long catalogTagCount,
                           long fieldTagCount,
                           long maskRuleCount,
                           long enabledMaskRuleCount,
                           long keyCount,
                           long activeKeyCount,
                           long sensitiveCatalogCount,
                           long maskLogCount,
                           List<LevelStat> levelDistribution,
                           List<TargetTypeStat> targetTypeDistribution) {
    }

    public record LevelStat(Integer level, String levelName, long count) {
    }

    public record TargetTypeStat(String targetType, long count) {
    }
}
