package com.share.task.engine;

import com.share.common.util.IdGen;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 行级校验值：按“列顺序 + 行顺序”把数据拼成规范串后取 MD5，
 * 用于批次写入校验与事后一致性核对（同一份数据两次计算结果必须一致）。
 */
public final class RowChecksum {

    private static final char FIELD_SEP = '\u0001';
    private static final char ROW_SEP = '\u0002';

    private RowChecksum() {
    }

    /** 计算数据行 MD5。 */
    public static String of(List<String> columns, List<? extends Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                for (String column : columns) {
                    sb.append(normalize(row.get(column))).append(FIELD_SEP);
                }
                sb.append(ROW_SEP);
            }
        }
        return IdGen.md5(sb.toString());
    }

    /** 估算数据字节数（UTF-8）。 */
    public static long bytesOf(List<String> columns, List<? extends Map<String, Object>> rows) {
        long bytes = 0;
        if (rows == null) {
            return bytes;
        }
        for (Map<String, Object> row : rows) {
            for (String column : columns) {
                String value = normalize(row.get(column));
                bytes += value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
            }
            bytes += columns.size();
        }
        return bytes;
    }

    /**
     * 值规范化：数值去掉多余小数位（1.0 与 1.0000 视为一致），
     * null 视为空串，其余按字符串输出，保证源/目标两侧计算结果可比。
     */
    private static String normalize(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (Exception e) {
                return value.toString();
            }
        }
        if (value instanceof java.time.LocalDate localDate) {
            return localDate.toString();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime().toString();
        }
        return value.toString();
    }
}
