package com.share.task.engine;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 日期标准化工具（FR-05 格式标准化算子）：把源端多种日期写法统一成 yyyy-MM-dd。
 */
public final class DateHelper {

    private static final DateTimeFormatter OUT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ISO_LOCAL_DATE
    );

    private DateHelper() {
    }

    /** 标准化为 yyyy-MM-dd；无法识别时原样返回字符串，避免脏数据阻断整批任务。 */
    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate.format(OUT_DATE);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate().format(OUT_DATE);
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(OUT_DATE);
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().format(OUT_DATE);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return value;
        }
        String candidate = text.replace('T', ' ');
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(candidate, formatter).toLocalDate().format(OUT_DATE);
            } catch (Exception ignored) {
                // 继续尝试日期格式
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, formatter).format(OUT_DATE);
            } catch (Exception ignored) {
                // 继续尝试下一种格式
            }
        }
        return value;
    }
}
