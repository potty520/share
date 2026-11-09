package com.share.share.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用小工具：日期/数字解析、逗号串切分、Map 值转换。
 */
public final class ShareUtils {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<DateTimeFormatter> DATETIME_FMTS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private ShareUtils() {
    }

    /** 解析日期字符串（支持 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / ISO-8601）。 */
    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 10 && v.charAt(4) == '-' && v.charAt(7) == '-') {
            return LocalDate.parse(v.substring(0, 10), DATE_FMT);
        }
        LocalDateTime dt = parseDateTime(v);
        return dt == null ? null : dt.toLocalDate();
    }

    /** 解析时间字符串；无法解析返回 null。 */
    public static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() == 10) {
            return LocalDate.parse(v, DATE_FMT).atStartOfDay();
        }
        for (DateTimeFormatter f : DATETIME_FMTS) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (Exception ignore) {
                // 尝试下一种格式
            }
        }
        try {
            return OffsetDateTime.parse(v).toLocalDateTime();
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 日期结束时刻（23:59:59），用于授权到期。 */
    public static LocalDateTime endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(23, 59, 59);
    }

    /** 逗号/中文逗号/分号分隔串切分为去空列表。 */
    public static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String part : value.split("[,，;；]")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** 兼容 Map 中的列名（忽略大小写与下划线）：entName / ent_name / ENT_NAME 归一为 entname。 */
    public static String normalizeKey(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase();
    }

    /** 在 Map 中按归一化列名取值。 */
    public static Object pickIgnoreCase(java.util.Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        String target = normalizeKey(key);
        for (java.util.Map.Entry<String, Object> e : row.entrySet()) {
            if (normalizeKey(e.getKey()).equals(target)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 对象转字符串（null 安全）。 */
    public static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 对象转 Integer（null / 非法返回 null）。 */
    public static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 对象转 Long（null / 非法返回 null）。 */
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return (long) Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 截断字符串到指定长度。 */
    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 安全转 int，越界返回 defaultValue。 */
    public static int intOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }
}
