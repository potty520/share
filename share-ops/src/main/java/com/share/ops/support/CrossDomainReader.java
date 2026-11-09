package com.share.ops.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨域只读访问器（契约第 0 章“跨域只读约定”）。
 * <p>只读 {@code tk_task_instance}、{@code tk_task}、{@code sw_batch}、{@code shr_call_stat}、
 * {@code ag_node}、{@code sys_user}，全部走 JdbcTemplate；其它域的 JPA Repository 一律不碰。</p>
 * <p>并行开发期间目标表可能尚未建好、或字段命名尚未最终确定，因此这里先做表/列存在性探测，
 * 缺表缺列时安全降级为空结果，绝不让监控/审计接口 500。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossDomainReader {

    private final JdbcTemplate jdbcTemplate;

    /** 表 → 列名（小写）缓存；空集合表示表不存在或不可读 */
    private final Map<String, Set<String>> columnCache = new ConcurrentHashMap<>();

    // ================= 元数据探测 =================

    public Set<String> columnsOf(String table) {
        return columnCache.computeIfAbsent(table, this::loadColumns);
    }

    public boolean hasTable(String table) {
        return !columnsOf(table).isEmpty();
    }

    private Set<String> loadColumns(String table) {
        Set<String> columns = new LinkedHashSet<>();
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return columns;
        }
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String candidate : new String[]{table, table.toUpperCase(), table.toLowerCase()}) {
                try (ResultSet rs = meta.getColumns(null, null, candidate, "%")) {
                    while (rs.next()) {
                        String name = rs.getString(4);
                        if (name != null) {
                            columns.add(name.toLowerCase());
                        }
                    }
                }
                if (!columns.isEmpty()) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("探测跨域表 {} 失败: {}", table, e.getMessage());
        }
        if (columns.isEmpty()) {
            // 只 warn 一次（结果已缓存），避免定时任务刷日志
            log.warn("跨域只读表 {} 不存在或不可读，相关聚合自动降级为空", table);
        }
        return columns;
    }

    // ================= 安全查询 =================

    public List<Map<String, Object>> queryList(String sql, Object... args) {
        try {
            return jdbcTemplate.queryForList(sql, args);
        } catch (Exception e) {
            log.debug("跨域只读查询失败 sql={}: {}", sql, e.getMessage());
            return List.of();
        }
    }

    public Optional<Map<String, Object>> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = queryList(sql, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long countOf(String sql, Object... args) {
        try {
            Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
            return v == null ? 0L : v;
        } catch (Exception e) {
            log.debug("跨域计数失败 sql={}: {}", sql, e.getMessage());
            return 0L;
        }
    }

    /** 取第一个存在的列名（列名大小写不敏感） */
    public String pick(Set<String> columns, String... candidates) {
        for (String c : candidates) {
            if (columns.contains(c.toLowerCase())) {
                return c.toLowerCase();
            }
        }
        return null;
    }

    /** 按存在性挑选列，拼 select 语句（列名来自硬编码白名单，无注入风险） */
    public String selectSql(String table, Set<String> columns, String... candidates) {
        List<String> picked = new ArrayList<>();
        for (String c : candidates) {
            if (columns.contains(c.toLowerCase())) {
                picked.add(c.toLowerCase());
            }
        }
        if (picked.isEmpty()) {
            return null;
        }
        return "select " + String.join(", ", picked) + " from " + table;
    }

    /** 读取行内字段（键名大小写不敏感） */
    public static Object val(Map<String, Object> row, String column) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object direct = row.get(column);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String str(Map<String, Object> row, String column) {
        Object v = val(row, column);
        return v == null ? null : String.valueOf(v);
    }

    public static long lng(Map<String, Object> row, String column) {
        Object v = val(row, column);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static LocalDateTime time(Map<String, Object> row, String column) {
        Object v = val(row, column);
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        try {
            return LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 业务查询：前置节点（ag_node） =================

    /** 前置节点列表（share-agent 为节点身份的权威来源） */
    public List<Map<String, Object>> agentNodes() {
        Set<String> columns = columnsOf("ag_node");
        if (columns.isEmpty()) {
            return List.of();
        }
        String sql = selectSql("ag_node", columns,
                "id", "node_code", "node_name", "node_type", "status", "ip", "port",
                "last_heartbeat_time", "last_heartbeat", "cpu_usage", "mem_usage", "disk_usage",
                "version", "dept_id", "node_group");
        if (sql == null) {
            return List.of();
        }
        String order = columns.contains("node_code") ? " order by node_code" : "";
        return queryList(sql + order);
    }

    // ================= 业务查询：任务实例 / 任务（share-task） =================

    /** 最近任务实例（监控总览成功率、运维报表） */
    public List<Map<String, Object>> recentTaskInstances(int limit) {
        Set<String> columns = columnsOf("tk_task_instance");
        if (columns.isEmpty()) {
            return List.of();
        }
        String sql = selectSql("tk_task_instance", columns,
                "id", "task_id", "task_name", "batch_id", "status", "row_count",
                "start_time", "end_time", "cost_ms", "error_msg", "dept_id", "create_time");
        if (sql == null) {
            return List.of();
        }
        String order = columns.contains("start_time") ? " order by start_time desc"
                : (columns.contains("id") ? " order by id desc" : "");
        return queryList(sql + order + " limit " + clampLimit(limit));
    }

    /** 按批次号查任务实例 */
    public Map<String, Object> taskInstanceByBatch(String batchId) {
        Set<String> columns = columnsOf("tk_task_instance");
        if (columns.isEmpty() || !columns.contains("batch_id")) {
            return null;
        }
        String sql = selectSql("tk_task_instance", columns,
                "id", "task_id", "task_name", "batch_id", "status", "row_count",
                "start_time", "end_time", "cost_ms", "error_msg", "dept_id");
        if (sql == null) {
            return null;
        }
        return queryOne(sql + " where batch_id = ?", batchId).orElse(null);
    }

    /** 任务定义（名称/部门回填） */
    public Map<String, Object> taskById(Long taskId) {
        if (taskId == null) {
            return null;
        }
        Set<String> columns = columnsOf("tk_task");
        if (columns.isEmpty() || !columns.contains("id")) {
            return null;
        }
        String sql = selectSql("tk_task", columns, "id", "task_name", "dept_id", "status", "task_type");
        if (sql == null) {
            return null;
        }
        return queryOne(sql + " where id = ?", taskId).orElse(null);
    }

    /** 任务 ID → 任务名称（运维报表任务健康度） */
    public Map<Long, String> taskNames() {
        Map<Long, String> map = new java.util.LinkedHashMap<>();
        Set<String> columns = columnsOf("tk_task");
        if (columns.isEmpty() || !columns.contains("id") || !columns.contains("task_name")) {
            return map;
        }
        for (Map<String, Object> row : queryList("select id, task_name from tk_task")) {
            Object id = val(row, "id");
            Object name = val(row, "task_name");
            if (id instanceof Number n && name != null) {
                map.put(n.longValue(), String.valueOf(name));
            }
        }
        return map;
    }

    // ================= 业务查询：交换批次（sw_batch / sw_transfer_log） =================

    public Map<String, Object> batch(String batchId) {
        Set<String> columns = columnsOf("sw_batch");
        if (columns.isEmpty() || !columns.contains("batch_id")) {
            return null;
        }
        String sql = selectSql("sw_batch", columns,
                "id", "batch_id", "task_id", "task_name", "row_count", "status", "checksum",
                "source_type", "source_id", "target_type", "target_id",
                "start_time", "end_time", "cost_ms", "error_msg", "dept_id");
        if (sql == null) {
            return null;
        }
        return queryOne(sql + " where batch_id = ?", batchId).orElse(null);
    }

    /** 批次内的流转日志（源 → 任务 → 目标） */
    public List<Map<String, Object>> swTransferLogs(String batchId) {
        Set<String> columns = columnsOf("sw_transfer_log");
        if (columns.isEmpty() || !columns.contains("batch_id")) {
            return List.of();
        }
        String sql = selectSql("sw_transfer_log", columns,
                "id", "batch_id", "stage", "source_type", "source_id", "source_name",
                "target_type", "target_id", "target_name", "row_count", "status",
                "message", "cost_ms", "transfer_time", "create_time");
        if (sql == null) {
            return List.of();
        }
        return queryList(sql + " where batch_id = ? order by id", batchId);
    }

    // ================= 业务查询：API 调用计量（shr_call_stat） =================

    /** 区间内开放 API 调用次数（用于总览与 API 指标聚合） */
    public long apiCallCount(LocalDateTime from, LocalDateTime to) {
        Set<String> columns = columnsOf("shr_call_stat");
        if (columns.isEmpty()) {
            return 0L;
        }
        String timeColumn = pick(columns, "stat_time", "call_time", "create_time", "call_date", "request_time");
        if (timeColumn == null) {
            return countOf("select count(*) from shr_call_stat");
        }
        return countOf("select count(*) from shr_call_stat where " + timeColumn + " >= ? and " + timeColumn + " < ?",
                from, to);
    }

    /** 区间内 API 调用失败次数（成功标志列缺失时按状态列模糊判断） */
    public long apiFailCount(LocalDateTime from, LocalDateTime to) {
        Set<String> columns = columnsOf("shr_call_stat");
        if (columns.isEmpty()) {
            return 0L;
        }
        String timeColumn = pick(columns, "stat_time", "call_time", "create_time", "call_date", "request_time");
        String flagColumn = pick(columns, "success_flag", "success", "is_success");
        String statusColumn = pick(columns, "status", "result", "call_status");
        String where = timeColumn == null ? "" : " where " + timeColumn + " >= ? and " + timeColumn + " < ?";
        Object[] args = timeColumn == null ? new Object[]{} : new Object[]{from, to};
        if (flagColumn != null) {
            return countOf("select count(*) from shr_call_stat" + where
                    + (where.isEmpty() ? " where " : " and ") + "(" + flagColumn + " = false or " + flagColumn + " = 0)",
                    args);
        }
        if (statusColumn != null) {
            return countOf("select count(*) from shr_call_stat" + where
                    + (where.isEmpty() ? " where " : " and ") + "(" + statusColumn + " like 'FAIL%' or "
                    + statusColumn + " like 'ERROR%')", args);
        }
        return 0L;
    }

    // ================= 业务查询：用户（sys_user） =================

    /** 用户 ID → 显示名（部门排行/审计辅助） */
    public Map<Long, String> usernames() {
        Map<Long, String> map = new java.util.LinkedHashMap<>();
        Set<String> columns = columnsOf("sys_user");
        if (columns.isEmpty() || !columns.contains("id")) {
            return map;
        }
        String nameColumn = pick(columns, "real_name", "username", "nickname");
        if (nameColumn == null) {
            return map;
        }
        for (Map<String, Object> row : queryList("select id, " + nameColumn + " from sys_user")) {
            Object id = val(row, "id");
            Object name = val(row, nameColumn);
            if (id instanceof Number n && name != null) {
                map.put(n.longValue(), String.valueOf(name));
            }
        }
        return map;
    }

    public static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }
}
