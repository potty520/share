package com.share.share.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台库表结构缓存。
 * <p>共享 API 的挂接表由目录/数据源域维护，本域不建 JPA 实体，只按需读取列名用于
 * 参数白名单校验、排序与结果字段映射。列名以平台库 information_schema 为准（H2 MySQL 模式）。</p>
 */
@Slf4j
@Component
public class TableMetaCache {

    private static final String SQL = """
            select column_name from information_schema.columns
             where lower(table_name) = lower(?)
               and (lower(table_schema) = 'public' or lower(table_schema) = lower(database()))
             order by ordinal_position
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    public TableMetaCache(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 返回表的全部列名（小写），表不存在时返回空列表（不缓存，便于建表后立即可用）。 */
    public List<String> columns(String table) {
        if (table == null || table.isBlank()) {
            return List.of();
        }
        String key = table.toLowerCase();
        List<String> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> cols;
        try {
            cols = jdbcTemplate.queryForList(SQL, String.class, key);
        } catch (Exception e) {
            log.debug("读取表结构失败 table={} err={}", table, e.getMessage());
            cols = List.of();
        }
        if (!cols.isEmpty()) {
            cache.put(key, cols);
        }
        return cols;
    }

    /** 表是否存在（用于种子数据绑定校验）。 */
    public boolean exists(String table) {
        return !columns(table).isEmpty();
    }

    /** 清理缓存（建表 / 表结构变更后调用）。 */
    public void evict(String table) {
        if (table == null) {
            cache.clear();
        } else {
            cache.remove(table.toLowerCase());
        }
    }
}
