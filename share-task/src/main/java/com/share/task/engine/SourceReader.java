package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.task.entity.TkTask;
import com.share.task.service.MetadataReadService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 源端读取器（FR-02）：支持平台内置表、外部 JDBC 表（均支持增量水位）与 CSV 文件；
 * 若任务登记了 sourceId，会跨域只读 ds_source 取连接信息写入流转日志，便于追溯。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceReader {

    private static final String IDENTIFIER_REGEX = "^[A-Za-z_][A-Za-z0-9_]*$";

    private final JdbcTemplate jdbcTemplate;

    private final EngineSettings settings;

    private final MetadataReadService metadataReadService;

    private final JdbcConnectionFactory jdbcConnectionFactory;

    /** 源端读取结果。 */
    @Getter
    @Setter
    public static class SourceData {
        /** 源类型：H2_TABLE / JDBC_TABLE / CSV_FILE */
        private String sourceType;
        /** 源端引用（表名或文件路径） */
        private String sourceRef;
        /** 数据行 */
        private List<Map<String, Object>> rows = new ArrayList<>();
        /** 连接/挂接信息描述（用于日志与追溯） */
        private String connectionInfo;
        /** 本次读取到的最大增量水位（增量任务成功后写回任务） */
        private String watermark;
    }

    /** 读取源端全部数据（受 share.engine.max-rows-per-run 限制）。 */
    public SourceData read(TkTask task) {
        SourceData data = new SourceData();
        String sourceType = resolveSourceType(task);
        data.setSourceType(sourceType);
        String connectionInfo = describeDataSource(task);
        data.setConnectionInfo(connectionInfo);

        if ("JDBC_TABLE".equals(sourceType)) {
            return readExternal(data, task);
        }

        if ("CSV_FILE".equals(sourceType)) {
            Path path = resolvePath(task.getSourceFilePath());
            data.setSourceRef(path.toString());
            if (!Files.exists(path)) {
                throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "源文件不存在：" + path);
            }
            try {
                List<Map<String, Object>> rows = CsvUtil.read(path);
                data.setRows(limit(rows));
            } catch (IOException e) {
                throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "读取源 CSV 失败：" + e.getMessage());
            }
            return data;
        }

        String table = task.getSourceTable();
        if (table == null || table.isBlank()) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "源表名为空，无法执行库表同步");
        }
        validateIdentifier(table, "源表名");
        data.setSourceRef(table);
        boolean incremental = "INCREMENT".equalsIgnoreCase(task.getTaskType());
        String incrementColumn = task.getSourceIncrementColumn();
        if (incremental) {
            if (incrementColumn == null || incrementColumn.isBlank()) {
                throw new BizException(ErrorCode.TASK_ENGINE_FAILED,
                        "增量任务未配置 sourceIncrementColumn，无法确定增量字段");
            }
            validateIdentifier(incrementColumn, "增量字段");
        }
        String watermark = incremental ? firstNonBlank(task.getLastWatermark(), task.getSourceIncrementStart()) : null;
        StringBuilder sql = new StringBuilder("select * from ").append(table);
        if (incremental && watermark != null) {
            sql.append(" where ").append(incrementColumn).append(" > ?");
        }
        if (incremental) {
            sql.append(" order by ").append(incrementColumn).append(" asc");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.execute(
                    (ConnectionCallback<List<Map<String, Object>>>) connection -> {
                        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                            if (incremental && watermark != null) {
                                statement.setObject(1, parseWatermark(watermark));
                            }
                            statement.setMaxRows(Math.max(1, settings.getMaxRowsPerRun()));
                            try (ResultSet resultSet = statement.executeQuery()) {
                                return readRows(resultSet);
                            }
                        }
                    });
            if (rows == null) {
                rows = new ArrayList<>();
            }
            List<Map<String, Object>> ordered = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                ordered.add(new LinkedHashMap<>(row));
            }
            ordered = stripManagedColumns(ordered);
            data.setRows(limit(ordered));
            if (incremental && !ordered.isEmpty()) {
                Object value = valueIgnoreCase(ordered.get(ordered.size() - 1), incrementColumn);
                data.setWatermark(formatWatermark(value));
            }
        } catch (Exception e) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED,
                    "读取源表 " + table + " 失败：" + e.getMessage());
        }
        return data;
    }

    /** 读取外部 JDBC 数据源；增量任务按水位字段过滤并记录本次最大水位。 */
    private SourceData readExternal(SourceData data, TkTask task) {
        JdbcConnectionFactory.JdbcEndpoint endpoint = jdbcConnectionFactory.resolve(task.getSourceId(), "源端");
        String table = task.getSourceTable();
        if (table == null || table.isBlank()) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "源表名为空，无法执行外部 JDBC 读取");
        }
        validateIdentifier(table, "源表名");
        boolean incremental = "INCREMENT".equalsIgnoreCase(task.getTaskType());
        String incrementColumn = task.getSourceIncrementColumn();
        if (incremental) {
            if (incrementColumn == null || incrementColumn.isBlank()) {
                throw new BizException(ErrorCode.TASK_ENGINE_FAILED,
                        "增量任务未配置 sourceIncrementColumn，无法确定增量字段");
            }
            validateIdentifier(incrementColumn, "增量字段");
        }
        String watermark = incremental ? firstNonBlank(task.getLastWatermark(), task.getSourceIncrementStart()) : null;
        StringBuilder sql = new StringBuilder("select * from ").append(table);
        if (incremental && watermark != null) {
            sql.append(" where ").append(incrementColumn).append(" > ?");
        }
        if (incremental) {
            sql.append(" order by ").append(incrementColumn).append(" asc");
        }
        List<Map<String, Object>> rows = endpoint.jdbc().execute((ConnectionCallback<List<Map<String, Object>>>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                if (incremental && watermark != null) {
                    statement.setObject(1, parseWatermark(watermark));
                }
                statement.setMaxRows(Math.max(1, settings.getMaxRowsPerRun()));
                try (ResultSet resultSet = statement.executeQuery()) {
                    return readRows(resultSet);
                }
            }
        });
        if (rows == null) {
            rows = new ArrayList<>();
        }
        rows = stripManagedColumns(rows);
        data.setSourceType("JDBC_TABLE");
        data.setSourceRef("ds#" + endpoint.id() + "." + table);
        data.setRows(rows);
        data.setConnectionInfo("ds_source[id=" + endpoint.id() + ", code=" + endpoint.code()
                + ", name=" + endpoint.name() + ", type=" + endpoint.type() + ", url=" + endpoint.redactedUrl() + "]");
        if (incremental && !rows.isEmpty()) {
            Object value = valueIgnoreCase(rows.get(rows.size() - 1), incrementColumn);
            data.setWatermark(formatWatermark(value));
        }
        return data;
    }

    private String formatWatermark(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toString().replace('T', ' ');
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString().replace('T', ' ');
        }
        if (value instanceof java.time.LocalDate localDate) {
            return localDate.toString();
        }
        return String.valueOf(value);
    }

    /** 引擎写入时附加的批次列/行序列属于保留列，二次同步时不再作为业务字段搬运。 */
    private List<Map<String, Object>> stripManagedColumns(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            row.keySet().removeIf(key -> key != null
                    && (TargetWriter.BATCH_COLUMN.equalsIgnoreCase(key)
                    || TargetWriter.ROW_COLUMN.equalsIgnoreCase(key)));
        }
        return rows;
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = resultSet.getMetaData();
        int columns = meta.getColumnCount();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                String label = meta.getColumnLabel(i);
                if (label == null || label.isBlank()) {
                    label = meta.getColumnName(i);
                }
                row.put(label, resultSet.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private Object parseWatermark(String value) {
        String text = value.trim();
        if (text.matches("[-+]?\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignore) {
                return text;
            }
        }
        if (text.matches("[-+]?\\d+\\.\\d+")) {
            try {
                return new java.math.BigDecimal(text);
            } catch (NumberFormatException ignore) {
                return text;
            }
        }
        return text;
    }

    private Object valueIgnoreCase(Map<String, Object> row, String key) {
        if (row == null || key == null) {
            return null;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : (second != null && !second.isBlank() ? second.trim() : null);
    }

    /** 读取指定表（质量检核/预览复用）。 */
    public List<Map<String, Object>> readTable(String table, int maxRows) {
        validateIdentifier(table, "表名");
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from " + table);
            if (maxRows > 0 && rows.size() > maxRows) {
                rows = new ArrayList<>(rows.subList(0, maxRows));
            }
            return rows;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取表 " + table + " 失败：" + e.getMessage());
        }
    }

    /** 源类型：显式配置优先，否则按“有文件路径 → CSV，有表名 → 库表”推断。 */
    public static String resolveSourceType(TkTask task) {
        if (task.getSourceType() != null && !task.getSourceType().isBlank()) {
            return task.getSourceType().trim().toUpperCase();
        }
        if (task.getSourceFilePath() != null && !task.getSourceFilePath().isBlank()) {
            return "CSV_FILE";
        }
        return "H2_TABLE";
    }

    /** 目标类型推断（目标端写入器复用）。 */
    public static String resolveTargetType(TkTask task) {
        if (task.getTargetType() != null && !task.getTargetType().isBlank()) {
            return task.getTargetType().trim().toUpperCase();
        }
        if (task.getTargetFilePath() != null && !task.getTargetFilePath().isBlank()) {
            return "CSV_FILE";
        }
        return "H2_TABLE";
    }

    /** 表名/列名白名单校验，避免 SQL 注入。 */
    public static void validateIdentifier(String name, String label) {
        if (name == null || !name.matches(IDENTIFIER_REGEX)) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + " 不合法：" + name);
        }
    }

    /** 相对路径按应用工作目录解析。 */
    public static Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir", ".")).resolve(filePath).normalize();
        }
        return path;
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> rows) {
        int max = settings.getMaxRowsPerRun();
        if (rows.size() > max) {
            log.warn("源端数据量 {} 超过单次上限 {}，仅处理前 {} 行", rows.size(), max, max);
            return new ArrayList<>(rows.subList(0, max));
        }
        return rows;
    }

    /** 跨域只读 ds_source 取连接信息（不参与实际连接，用于日志留痕与追溯）。 */
    private String describeDataSource(TkTask task) {
        StringBuilder info = new StringBuilder();
        if (task.getSourceId() != null) {
            Optional<Map<String, Object>> source = metadataReadService.findDataSource(task.getSourceId());
            if (source.isEmpty()) {
                info.append("sourceId=").append(task.getSourceId()).append("（ds_source 无记录，使用内置样例源）");
            } else {
                Map<String, Object> map = source.get();
                String name = MetadataReadService.pick(map, "sourceName", "source_name", "name");
                String type = MetadataReadService.pick(map, "sourceType", "source_type", "type");
                String host = MetadataReadService.pick(map, "host");
                String port = MetadataReadService.pick(map, "port");
                String dbName = MetadataReadService.pick(map, "dbName", "db_name");
                String jdbcUrl = MetadataReadService.pick(map, "jdbcUrl", "jdbc_url");
                String owner = MetadataReadService.pick(map, "owner", "deptName", "dept_name");
                info.append("ds_source[id=").append(task.getSourceId())
                        .append(", name=").append(name)
                        .append(", type=").append(type)
                        .append(", host=").append(host)
                        .append(':').append(port)
                        .append(", db=").append(dbName)
                        .append(", jdbcUrl=").append(jdbcUrl == null ? "未配置" : "已配置")
                        .append(", owner=").append(owner)
                        .append("]");
            }
        }
        // 目录资源挂接信息与密级（跨域只读 cat_catalog，缺失时跳过）
        if (task.getCatalogId() != null) {
            Optional<Map<String, Object>> catalog = metadataReadService.findCatalog(task.getCatalogId());
            catalog.ifPresent(map -> {
                String resourceName = MetadataReadService.pick(map, "resourceName", "resource_name");
                String level = MetadataReadService.pick(map, "level");
                String tableName = MetadataReadService.pick(map, "tableName", "table_name");
                if (info.length() > 0) {
                    info.append("; ");
                }
                info.append("cat_catalog[id=").append(task.getCatalogId())
                        .append(", name=").append(resourceName)
                        .append(", attachTable=").append(tableName)
                        .append(", level=").append(level)
                        .append("]");
            });
        }
        return info.length() == 0 ? null : info.toString();
    }
}
