package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.task.entity.TkTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * 目标端写入器（FR-02）：支持 H2 目标表（自动建表 + 幂等 upsert）与 CSV 文件落盘。
 * <p>幂等策略：目标表附加 sw_batch_id / sw_row_no 两列，写入前先删除同批次数据再批量插入；
 * 同一批次重复执行不会产生重复数据，可安全重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetWriter {

    /** 批次列名 */
    public static final String BATCH_COLUMN = "sw_batch_id";

    /** 行序列 */
    public static final String ROW_COLUMN = "sw_row_no";

    private final JdbcTemplate jdbcTemplate;

    private final EngineSettings settings;

    /** 写入结果。 */
    @Getter
    @Setter
    public static class WriteResult {
        /** 实际写入行数 */
        private long writtenRows;
        /** 写入字节数（估算/文件大小） */
        private long bytes;
        /** 目标引用（表名或文件路径） */
        private String targetRef;
        /** 是否命中幂等跳过 */
        private boolean skipped;
    }

    /**
     * 写入目标端。
     *
     * @param task     任务定义（提供目标配置）
     * @param columns  数据列（有序）
     * @param rows     数据行
     * @param batchId  批次号（幂等键）
     * @param progress 进度回调：已写入行数
     */
    public WriteResult write(TkTask task, List<String> columns, List<Map<String, Object>> rows,
                             String batchId, LongConsumer progress) {
        WriteResult result = new WriteResult();
        String targetType = SourceReader.resolveTargetType(task);
        if ("CSV_FILE".equals(targetType)) {
            return writeCsv(task, columns, rows, progress);
        }
        return writeTable(task, columns, rows, batchId, progress);
    }

    /** 写入 H2 目标表：自动建表 + 增量补列 + 按批次幂等覆盖。 */
    private WriteResult writeTable(TkTask task, List<String> columns, List<Map<String, Object>> rows,
                                   String batchId, LongConsumer progress) {
        String table = task.getTargetTable();
        if (table == null || table.isBlank()) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "目标表名为空，无法执行库表同步");
        }
        SourceReader.validateIdentifier(table, "目标表名");
        for (String column : columns) {
            SourceReader.validateIdentifier(column, "目标列名");
        }
        WriteResult result = new WriteResult();
        result.setTargetRef(table);

        ensureTable(table, columns, rows);

        // 幂等：同一批次号的历史数据先删除，重复执行不产生重复记录
        jdbcTemplate.update("delete from " + table + " where " + BATCH_COLUMN + " = ?", batchId);

        if (rows.isEmpty()) {
            result.setWrittenRows(0);
            return result;
        }
        List<String> insertColumns = new ArrayList<>(columns);
        insertColumns.add(BATCH_COLUMN);
        insertColumns.add(ROW_COLUMN);
        StringBuilder sql = new StringBuilder("insert into ").append(table).append(" (");
        for (int i = 0; i < insertColumns.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(insertColumns.get(i));
        }
        sql.append(") values (");
        sql.append("?,".repeat(insertColumns.size()));
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
                for (int c = 0; c < columns.size(); c++) {
                    values[c] = toJdbcValue(row.get(columns.get(c)));
                }
                values[columns.size()] = batchId;
                values[columns.size() + 1] = (long) i + 1;
                args.add(values);
            }
            jdbcTemplate.batchUpdate(sql.toString(), args);
            written += args.size();
            index = end;
            if (progress != null) {
                progress.accept(written);
            }
        }
        result.setWrittenRows(written);
        result.setBytes(RowChecksum.bytesOf(columns, rows));
        return result;
    }

    /** 写入 CSV：覆盖目标文件（等价于删除旧批次后重新落盘）。 */
    private WriteResult writeCsv(TkTask task, List<String> columns, List<Map<String, Object>> rows,
                                 LongConsumer progress) {
        String filePath = task.getTargetFilePath();
        if (filePath == null || filePath.isBlank()) {
            filePath = "data/exchange/target/" + task.getTaskCode() + ".csv";
        }
        Path path = Paths.get(filePath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir", ".")).resolve(filePath).normalize();
        }
        try {
            CsvUtil.write(path, columns, rows);
        } catch (Exception e) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "写入目标 CSV 失败：" + e.getMessage());
        }
        if (progress != null) {
            progress.accept((long) rows.size());
        }
        WriteResult result = new WriteResult();
        result.setTargetRef(path.toString());
        result.setWrittenRows(rows.size());
        try {
            result.setBytes(Files.size(path));
        } catch (Exception e) {
            result.setBytes(RowChecksum.bytesOf(columns, rows));
        }
        return result;
    }

    /** 目标表不存在则建表；已存在则补齐缺失列（自动适配映射变化）。 */
    public void ensureTable(String table, List<String> columns, List<Map<String, Object>> rows) {
        boolean exists = tableExists(table);
        if (!exists) {
            LinkedHashSet<String> allColumns = new LinkedHashSet<>(columns);
            StringBuilder ddl = new StringBuilder("create table if not exists ").append(table).append(" (");
            boolean first = true;
            for (String column : allColumns) {
                if (!first) {
                    ddl.append(',');
                }
                ddl.append(column).append(' ').append(inferSqlType(column, rows));
                first = false;
            }
            ddl.append(',').append(BATCH_COLUMN).append(" varchar(64)");
            ddl.append(',').append(ROW_COLUMN).append(" bigint");
            ddl.append(')');
            jdbcTemplate.execute(ddl.toString());
            return;
        }
        Set<String> existing = new HashSet<>();
        for (String column : listColumns(table)) {
            existing.add(column.toLowerCase());
        }
        for (String column : columns) {
            if (!existing.contains(column.toLowerCase())) {
                jdbcTemplate.execute("alter table " + table + " add column " + column + " " + inferSqlType(column, rows));
            }
        }
        if (!existing.contains(BATCH_COLUMN)) {
            jdbcTemplate.execute("alter table " + table + " add column " + BATCH_COLUMN + " varchar(64)");
        }
        if (!existing.contains(ROW_COLUMN)) {
            jdbcTemplate.execute("alter table " + table + " add column " + ROW_COLUMN + " bigint");
        }
    }

    /** 判断表是否存在（information_schema，MySQL/H2 通用）。 */
    public boolean tableExists(String table) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.tables where lower(table_name) = lower(?)",
                    Integer.class, table);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("查询表信息失败：{}", e.getMessage());
            return false;
        }
    }

    /** 列出表已有列。 */
    public List<String> listColumns(String table) {
        try {
            return jdbcTemplate.queryForList(
                    "select column_name from information_schema.columns where lower(table_name) = lower(?)",
                    String.class, table);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 依据样例值推断列类型：
     * 数值列用 bigint/decimal；字符串列按最长样例值选择 VARCHAR 长度，超过 1000 字符用 TEXT，
     * 避免 MySQL utf8mb4 下多个长 VARCHAR 累计导致单行超过 65535 字节而建表失败。
     */
    private String inferSqlType(String column, List<Map<String, Object>> rows) {
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
            return "varchar(255)";
        }
        if (allIntegral) {
            return "bigint";
        }
        if (anyDecimal) {
            return "decimal(20,4)";
        }
        if (maxLength <= 64) {
            return "varchar(128)";
        }
        if (maxLength <= 255) {
            return "varchar(255)";
        }
        if (maxLength <= 1000) {
            return "varchar(1000)";
        }
        return "text";
    }

    /** Java 值 → JDBC 参数。 */
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

    /** 解析目标 CSV 路径（供一致性核对复用）。 */
    public static Path resolveTargetPath(TkTask task) {
        String filePath = task.getTargetFilePath();
        if (filePath == null || filePath.isBlank()) {
            filePath = "data/exchange/target/" + task.getTaskCode() + ".csv";
        }
        Path path = Paths.get(filePath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir", ".")).resolve(filePath).normalize();
        }
        return path;
    }
}
