package com.share.catalog.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 部门名称解析：跨域只读 {@code sys_dept}（契约允许的唯一跨域读表）。
 * <p>share-auth 的种子数据可能晚于本域装配，因此读取失败时退化为内置演示部门名称，
 * 并在首次成功读取后写入本地缓存，避免每次请求都查库。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeptNameResolver {

    /** 演示环境内置部门（与 SeedConstants 一致），作为库未就绪时的兜底。 */
    private static final Map<Long, String> FALLBACK = new LinkedHashMap<>();

    static {
        FALLBACK.put(1L, "数据中心");
        FALLBACK.put(2L, "财务部");
        FALLBACK.put(3L, "运营管理部");
        FALLBACK.put(4L, "人力资源部");
        FALLBACK.put(9L, "平台运维部");
    }

    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, String> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    /** 解析部门名称：库 → 缓存 → 内置演示部门。 */
    public String resolve(Long deptId) {
        if (deptId == null) {
            return null;
        }
        String cached = cache.get(deptId);
        if (cached != null) {
            return cached;
        }
        loadFromDb();
        cached = cache.get(deptId);
        return cached != null ? cached : FALLBACK.get(deptId);
    }

    /** 全部部门名称（供目录统计按部门分布使用）。 */
    public Map<Long, String> all() {
        loadFromDb();
        Map<Long, String> result = new LinkedHashMap<>(FALLBACK);
        result.putAll(cache);
        return result;
    }

    /** 一次批量回填：deptId 为空时以库/内置数据补全。 */
    public String resolveOrKeep(Long deptId, String current) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return resolve(deptId);
    }

    private void loadFromDb() {
        if (loaded) {
            return;
        }
        synchronized (cache) {
            if (loaded) {
                return;
            }
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from sys_dept");
                for (Map<String, Object> row : rows) {
                    Object idVal = valueOf(row, "id");
                    Object nameVal = valueOf(row, "dept_name");
                    if (nameVal == null) {
                        nameVal = valueOf(row, "deptname");
                    }
                    if (idVal instanceof Number num && nameVal != null) {
                        cache.put(num.longValue(), String.valueOf(nameVal));
                    }
                }
            } catch (Exception e) {
                // sys_dept 尚未建表（share-auth 未装配/未初始化）时保持兜底，不阻断本域
                log.debug("读取 sys_dept 失败，使用内置部门名称兜底: {}", e.getMessage());
            } finally {
                loaded = true;
            }
        }
    }

    /** 忽略大小写取值（H2 在不同配置下返回的列名大小写不一致）。 */
    private Object valueOf(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) {
            return v;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
