package com.share.security.service;

import com.share.security.dto.SecurityVOs;
import com.share.security.repository.SecClassifyTagRepository;
import com.share.security.repository.SecClassifyTemplateRepository;
import com.share.security.repository.SecKeyMetaRepository;
import com.share.security.repository.SecMaskRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 安全域概览（FR-10）：分级分布、规则数、密钥数、敏感资源数。
 * <p>敏感资源数通过跨域只读约定读取 cat_catalog（列名做多候选兼容，表未就绪时降级为标签统计）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityOverviewService {

    private final SecClassifyTemplateRepository templateRepository;
    private final SecClassifyTagRepository tagRepository;
    private final SecMaskRuleRepository maskRuleRepository;
    private final SecKeyMetaRepository keyMetaRepository;
    private final MaskLogService maskLogService;
    private final JdbcTemplate jdbcTemplate;

    /** 敏感数据默认密级（3 级及以上视为涉敏） */
    @Value("${share.security.sensitive-level:3}")
    private int sensitiveLevel;

    public SecurityVOs.Overview overview() {
        long tagCount = tagRepository.countByDeletedFalse();
        long catalogTagCount = tagRepository.countByDeletedFalseAndTargetType("CATALOG");
        long fieldTagCount = tagRepository.countByDeletedFalseAndTargetType("FIELD");

        List<SecurityVOs.LevelStat> levelDistribution = tagRepository.countGroupByLevel().stream()
                .map(row -> {
                    Integer level = row[0] == null ? null : ((Number) row[0]).intValue();
                    long count = row[1] == null ? 0 : ((Number) row[1]).longValue();
                    return new SecurityVOs.LevelStat(level, levelName(level), count);
                })
                .toList();

        List<SecurityVOs.TargetTypeStat> targetTypeDistribution = tagRepository.countGroupByTargetType().stream()
                .map(row -> new SecurityVOs.TargetTypeStat(
                        row[0] == null ? null : String.valueOf(row[0]),
                        row[1] == null ? 0 : ((Number) row[1]).longValue()))
                .toList();

        long sensitiveCatalogCount = countSensitiveCatalog();

        return new SecurityVOs.Overview(
                templateRepository.countByDeletedFalse(),
                tagCount,
                catalogTagCount,
                fieldTagCount,
                maskRuleRepository.countByDeletedFalse(),
                maskRuleRepository.countByDeletedFalseAndEnabledTrue(),
                keyMetaRepository.countByDeletedFalse(),
                keyMetaRepository.countByDeletedFalseAndKeyStatus("ACTIVE"),
                sensitiveCatalogCount,
                maskLogService.count(),
                levelDistribution,
                targetTypeDistribution);
    }

    /**
     * 统计敏感资源数：按跨域只读约定读取 cat_catalog，密级列 level，仅统计已发布资源。
     *
     * @return 统计值；catalog 表尚未建表/不可读时返回 0（不阻断概览接口）
     */
    private long countSensitiveCatalog() {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from cat_catalog where level >= ? and status = 'PUBLISHED'",
                    Long.class, sensitiveLevel);
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.debug("cat_catalog 暂不可读，敏感资源数按 0 计: {}", e.getMessage());
            return 0L;
        }
    }

    private String levelName(Integer level) {
        if (level == null || level < 1 || level > 4) {
            return "未分级";
        }
        return SecurityMaskService.LEVEL_NAMES[level - 1];
    }
}
