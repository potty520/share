package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.task.entity.TkTask;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * 外部 JDBC 目标表写入器（FR-02）：自动建表/补列、按批次幂等覆盖。
 * <p>与平台内置目标共用一个写入契约，但 DDL 依据真实数据库方言生成。</p>
 */
@Component
@RequiredArgsConstructor
public class ExternalJdbcTableWriter {

    private final JdbcConnectionFactory jdbcConnectionFactory;

    private final EngineSettings settings;

    public TargetWriter.WriteResult write(TkTask task, List<String> columns,
                                          List<Map<String, Object>> rows, String batchId,
                                          LongConsumer progress) {
        JdbcConnectionFactory.JdbcEndpoint endpoint = jdbcConnectionFactory.resolve(task.getTargetId(), "目标端");
        JdbcTemplate jdbc = endpoint.jdbc();
        DatabaseDialect dialect = endpoint.dialect();
        String table = task.getTargetTable();
        if (table == null || table.isBlank()) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "目标表名为空，无法执行 JDBC 写入");
        }
        SourceReader.validateIdentifier(table, "目标表名");
        List<String> dataColumns = businessColumns(columns);
        for (String column : dataColumns) {
            SourceReader.validateIdentifier(column, "目标列名");
        }

        ensureTable(jdbc, dialect, table, dataColumns, rows);
        if ("FULL".equalsIgnoreCase(task.getTaskType())) {
            jdbc.update("delete from " + table);
        } else {
            jdbc.update("delete from " + table + " where " + TargetWriter.BATCH_COLUMN + " = ?", batchId);
        }

        TargetWriter.WriteResult result = new TargetWriter.WriteResult();
        result.setTargetRef("ds#" + endpoint.id() + "." + table);
        if (rows.isEmpty()) {
            result.setWrittenRows(0);
            return result;
        }

        List<String> insertColumns = new ArrayList<>(dataColumns);
        insertColumns.add(TargetWriter.BATCH_COLUMN);
        insertColumns.add(TargetWriter.ROW_COLUMN);
        StringBuilder sql = new StringBuilder("insert into ").append(table).append(" (");
        for (int i = 0; i < insertColumns.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(insertColumns.get(i));
        }
        sql.append(") values (").append("?,".repeat(insertColumns.size()));
        sql.setLength(sql.length() - 1);
        sql.append(')');

        int batchSize = Math.max(1, settings.getBatchSize());
        long written = 0;
        int index = 0;
        while (index < rows.size()) {
            int end = Math.min(index + batchSize, rows.size());
            List<Object[]> args = new ArrayList<>(end - index);
            for (int i = index; i < end; i++) {
                Map<String, Object> row = rows.get(i);
                Object[] values = new Object[insertColumns.size()];
                for (int c = 0; c < dataColumns.size(); c++) {
                    values[c] = toJdbcValue(row.get(dataColumns.get(c)));
                }
                values[dataColumns.size()] = batchId;
                values[dataColumns.size() + 1] = (long) i + 1;
                args.add(values);
            }
            jdbc.batchUpdate(sql.toString(), args);
            written += args.size();
            index = end;
            if (progress != null) {
                progress.accept(written);
            }
        }
        result.setWrittenRows(written);
        result.setBytes(RowChecksum.bytesOf(dataColumns, rows));
        return result;
    }

    private List<String> businessColumns(List<String> columns) {
        List<String> dataColumns = new ArrayList<>();
        for (String column : columns) {
            if (!TargetWriter.BATCH_COLUMN.equalsIgnoreCase(column)
                    && !TargetWriter.ROW_COLUMN.equalsIgnoreCase(column)) {
                dataColumns.add(column);
            }
        }
        return dataColumns;
    }

    private void ensureTable(JdbcTemplate jdbc, DatabaseDialect dialect, String table,
                             List<String> columns, List<Map<String, Object>> rows) {
        if (!tableExists(jdbc, table)) {
            LinkedHashSet<String> allColumns = new LinkedHashSet<>(columns);
            StringBuilder ddl = new StringBuilder("create table ").append(table).append(" (");
            boolean first = true;
            for (String column : allColumns) {
                if (!first) {
                    ddl.append(',');
                }
                ddl.append(column).append(' ').append(inferSqlType(dialect, column, rows));
                first = false;
            }
            ddl.append(',').append(TargetWriter.BATCH_COLUMN).append(' ')
                    .append(dialect.varcharType(64));
            ddl.append(',').append(TargetWriter.ROW_COLUMN).append(' ').append(dialect.bigintType());
            ddl.append(')');
            jdbc.execute(ddl.toString());
            return;
        }

        Set<String> existing = new HashSet<>();
        for (String column : listColumns(jdbc, table)) {
            existing.add(column.toLowerCase(Locale.ROOT));
        }
        for (String column : columns) {
            if (!existing.contains(column.toLowerCase(Locale.ROOT))) {
                jdbc.execute("alter table " + table + " add column " + column + " "
                        + inferSqlType(dialect, column, rows));
            }
        }
        if (!existing.contains(TargetWriter.BATCH_COLUMN)) {
            jdbc.execute("alter table " + table + " add column " + TargetWriter.BATCH_COLUMN
                    + " " + dialect.varcharType(64));
        }
        if (!existing.contains(TargetWriter.ROW_COLUMN)) {
            jdbc.execute("alter table " + table + " add column " + TargetWriter.ROW_COLUMN
                    + " " + dialect.bigintType());
        }
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            for (String candidate : nameCandidates(table)) {
                try (ResultSet tables = meta.getTables(connection.getCatalog(), null, candidate,
                        new String[]{"TABLE"})) {
                    if (tables.next()) {
                        return true;
                    }
                }
            }
            return false;
        }));
    }

    private List<String> listColumns(JdbcTemplate jdbc, String table) {
        List<String> columns = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            List<String> names = new ArrayList<>();
            DatabaseMetaData meta = connection.getMetaData();
            for (String candidate : nameCandidates(table)) {
                try (ResultSet result = meta.getColumns(connection.getCatalog(), null, candidate, "%")) {
                    while (result.next()) {
                        names.add(result.getString("COLUMN_NAME"));
                    }
                }
                if (!names.isEmpty()) {
                    return names;
                }
            }
            return names;
        });
        return columns == null ? new ArrayList<>() : columns;
    }

    private List<String> nameCandidates(String table) {
        return List.of(table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT));
    }

    private String inferSqlType(DatabaseDialect dialect, String column, List<Map<String, Object>> rows) {
        boolean hasValue = false;
        boolean allIntegral = true;
        boolean anyDecimal = false;
        int maxLength = 0;
        int sample = 0;
        for (Map<String, Object> row : rows) {
            if (sample >= 200) {
                break;
            }
            sample++;
            Object value = row.get(column);
            if (value == null) {
                continue;
            }
            if (value instanceof LocalDateTime || value instanceof Timestamp
                    || value instanceof java.time.OffsetDateTime) {
                return dialect.dateTimeType();
            }
            if (value instanceof LocalDate || value instanceof java.sql.Date) {
                return dialect.dateType();
            }
            String text = String.valueOf(value);
            if (text.isBlank()) {
                continue;
            }
            hasValue = true;
            maxLength = Math.max(maxLength, text.length());
            if (value instanceof Long || value instanceof Integer || value instanceof Short
                    || (value instanceof java.math.BigDecimal decimal && decimal.scale() <= 0)) {
                continue;
            }
            allIntegral = false;
            if (value instanceof Double || value instanceof Float
                    || value instanceof java.math.BigDecimal) {
                anyDecimal = true;
            }
        }
        if (!hasValue) {
            return dialect.varcharType(255);
        }
        if (allIntegral) {
            return dialect.bigintType();
        }
        if (anyDecimal) {
            return dialect.decimalType();
        }
        if (maxLength <= 64) {
            return dialect.varcharType(128);
        }
        if (maxLength <= 255) {
            return dialect.varcharType(255);
        }
        if (maxLength <= 1000) {
            return dialect.varcharType(1000);
        }
        return dialect.textType();
    }

    private Object toJdbcValue(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        if (value instanceof LocalDate localDate) {
            return java.sql.Date.valueOf(localDate);
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return Timestamp.from(offsetDateTime.toInstant());
        }
        if (value instanceof Number || value instanceof String || value == null) {
            return value;
        }
        return String.valueOf(value);
    }
}
