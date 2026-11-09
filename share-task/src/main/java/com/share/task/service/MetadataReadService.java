package com.share.task.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨域只读元数据访问（契约 0 节“跨域只读约定”）：
 * share-task 只允许通过 JdbcTemplate 读 ds_source（取数据源连接信息）与 cat_catalog（取目录挂接信息），
 * 禁止依赖其他域的 JPA Repository。表结构由对应域维护，本服务做容错读取，缺失时降级不影响交换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataReadService {

    private static final String SQL_DATA_SOURCE = "select * from ds_source where id = ?";
    private static final String SQL_CATALOG = "select * from cat_catalog where id = ?";

    private final JdbcTemplate jdbcTemplate;

    /** 读取数据源登记信息（只读）。 */
    public Optional<Map<String, Object>> findDataSource(Long sourceId) {
        if (sourceId == null) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SQL_DATA_SOURCE, sourceId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception e) {
            log.debug("跨域读取 ds_source 失败（数据源域可能尚未初始化）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 读取目录资源挂接信息（只读）。 */
    public Optional<Map<String, Object>> findCatalog(Long catalogId) {
        if (catalogId == null) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SQL_CATALOG, catalogId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception e) {
            log.debug("跨域读取 cat_catalog 失败（目录域可能尚未初始化）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 从元数据 Map 中按候选键名取值（不同域字段命名可能有差异，做兼容匹配）。 */
    public static String pick(Map<String, Object> map, String... candidates) {
        if (map == null || candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            Object value = map.get(candidate);
            if (value == null) {
                value = map.get(candidate.toLowerCase());
            }
            if (value == null) {
                value = map.get(candidate.toUpperCase());
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
