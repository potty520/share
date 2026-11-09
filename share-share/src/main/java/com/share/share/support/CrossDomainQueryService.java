package com.share.share.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨域只读查询（契约 0 章：共享库 + JdbcTemplate，禁止读其它域的 JPA Repository）。
 * <p>本域只读 cat_catalog（目录名称/密级/挂接）、ds_source（数据源连接信息）、
 * sec_mask_rule（脱敏规则）。因为这些表由其它域负责创建，查询全部做容错：
 * 表不存在或列名有差异时降级为“未知”，不阻塞本域功能。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossDomainQueryService {

    private final JdbcTemplate jdbcTemplate;

    /** 目录资源引用。 */
    public record CatalogRef(Long id, String code, String name, Integer level, Long deptId,
                             Long sourceId, String tableName, String shareType) {
    }

    /** 脱敏规则引用（sec_mask_rule：mask_type / param / target_field / match_pattern / level_min / enabled）。 */
    public record MaskRuleRef(String field, String maskType, String param, boolean enabled,
                              String matchPattern, Integer levelMin) {
    }

    // ==================== cat_catalog ====================

    public Optional<CatalogRef> findCatalog(Long catalogId) {
        if (catalogId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = safeQuery("select * from cat_catalog where id = ?", catalogId);
        return rows.isEmpty() ? Optional.empty() : toCatalog(rows.get(0));
    }

    /** 按资源编码查目录（契约建议的绑定方式）。 */
    public Optional<CatalogRef> findCatalogByCode(String resourceCode) {
        if (resourceCode == null || resourceCode.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = safeQuery(
                "select * from cat_catalog where lower(resource_code) = lower(?)", resourceCode);
        return rows.isEmpty() ? Optional.empty() : toCatalog(rows.get(0));
    }

    /** 按资源名称模糊匹配（resource_code 对不上时的兜底绑定方式）。 */
    public Optional<CatalogRef> findCatalogByName(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = safeQuery(
                "select * from cat_catalog where lower(resource_name) like ? order by id asc limit 1",
                "%" + keyword.toLowerCase() + "%");
        return rows.isEmpty() ? Optional.empty() : toCatalog(rows.get(0));
    }

    /** 取目录密级（读不到时返回 0，表示未知/非敏感）。 */
    public int catalogLevel(Long catalogId) {
        return findCatalog(catalogId).map(c -> c.level() == null ? 1 : c.level()).orElse(1);
    }

    private Optional<CatalogRef> toCatalog(Map<String, Object> row) {
        Long id = ShareUtils.toLong(pick(row, "id"));
        String code = ShareUtils.str(pick(row, "resource_code", "code", "resourceCode"));
        String name = ShareUtils.str(pick(row, "resource_name", "name", "catalog_name", "resourceName"));
        // 目录密级列名为 level（1~4），兼容 secret_level / secretLevel 等历史写法
        Integer level = ShareUtils.toInt(pick(row, "level", "secret_level", "secretLevel", "security_level"));
        Long deptId = ShareUtils.toLong(pick(row, "dept_id", "deptId", "provide_dept_id"));
        Long sourceId = ShareUtils.toLong(pick(row, "source_id", "sourceId"));
        String tableName = ShareUtils.str(pick(row, "table_name", "tableName", "source_table"));
        String shareType = ShareUtils.str(pick(row, "share_type", "shareType"));
        return Optional.of(new CatalogRef(id, code, name, level, deptId, sourceId, tableName, shareType));
    }

    // ==================== ds_source ====================

    /** 数据源连接信息（仅在补全路由配置时使用；口令不回显）。 */
    public Optional<Map<String, Object>> findSource(Long sourceId) {
        if (sourceId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = safeQuery("select * from ds_source where id = ?", sourceId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ==================== sec_mask_rule ====================

    /** 全部启用中的脱敏规则（target_field → 算法，支持按 match_pattern 泛化匹配）。 */
    public List<MaskRuleRef> maskRules() {
        List<Map<String, Object>> rows = safeQuery("select * from sec_mask_rule");
        if (rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(row -> {
            String field = ShareUtils.str(pick(row, "target_field", "field_name", "fieldName", "field",
                    "mask_field", "name"));
            String type = ShareUtils.str(pick(row, "mask_type", "maskType", "type", "algorithm"));
            String param = ShareUtils.str(pick(row, "param", "mask_param", "maskParam", "params"));
            String pattern = ShareUtils.str(pick(row, "match_pattern", "matchPattern"));
            Integer levelMin = ShareUtils.toInt(pick(row, "level_min", "levelMin"));
            Object enabled = pick(row, "enabled");
            Object status = pick(row, "status");
            boolean on = enabled == null
                    ? !"DISABLED".equalsIgnoreCase(ShareUtils.str(status))
                    : Boolean.TRUE.equals(enabled) || "1".equals(ShareUtils.str(enabled))
                    || "true".equalsIgnoreCase(ShareUtils.str(enabled));
            return new MaskRuleRef(field, type, param, on, pattern, levelMin);
        }).filter(r -> r.field() != null || r.matchPattern() != null).toList();
    }

    // ==================== 内部工具 ====================

    private List<Map<String, Object>> safeQuery(String sql, Object... args) {
        try {
            return jdbcTemplate.queryForList(sql, args);
        } catch (Exception e) {
            log.debug("跨域只读查询失败（表或列可能尚未创建）: {} -> {}", sql, e.getMessage());
            return List.of();
        }
    }

    private Object pick(Map<String, Object> row, String... candidates) {
        for (String c : candidates) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (ShareUtils.normalizeKey(e.getKey()).equals(ShareUtils.normalizeKey(c)) && e.getValue() != null) {
                    return e.getValue();
                }
            }
        }
        return null;
    }
}
