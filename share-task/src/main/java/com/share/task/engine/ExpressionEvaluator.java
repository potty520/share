package com.share.task.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 轻量条件表达式求值器（FR-05 条件过滤）：
 * 支持单条件与 AND/OR 组合，运算符 = == != <> > >= < <=、contains、in、is null、is not null。
 * <p>示例：{@code status = 'NORMAL'}、{@code age >= 18}、{@code name contains 张三 AND type in (1,2)}。</p>
 */
public final class ExpressionEvaluator {

    private static final Pattern AND_SPLIT = Pattern.compile("(?i)\\s+and\\s+");
    private static final Pattern OR_SPLIT = Pattern.compile("(?i)\\s+or\\s+");

    private ExpressionEvaluator() {
    }

    /** 判断一行数据是否满足条件；条件为空视为满足。 */
    public static boolean test(String condition, Map<String, Object> row) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String expr = condition.trim();
        String[] orParts = OR_SPLIT.split(expr);
        if (orParts.length > 1) {
            for (String part : orParts) {
                if (testSingle(part, row)) {
                    return true;
                }
            }
            return false;
        }
        String[] andParts = AND_SPLIT.split(expr);
        for (String part : andParts) {
            if (!testSingle(part, row)) {
                return false;
            }
        }
        return true;
    }

    private static boolean testSingle(String condition, Map<String, Object> row) {
        String expr = condition.trim();
        if (expr.isEmpty()) {
            return true;
        }
        String lower = expr.toLowerCase();
        if (lower.endsWith(" is not null")) {
            String field = expr.substring(0, lower.lastIndexOf(" is not null")).trim();
            return valueOf(row, field) != null;
        }
        if (lower.endsWith(" is null")) {
            String field = expr.substring(0, lower.lastIndexOf(" is null")).trim();
            return valueOf(row, field) == null;
        }
        String[] operators = {">=", "<=", "!=", "<>", "==", "=", ">", "<"};
        for (String operator : operators) {
            int index = indexOfOperator(expr, operator);
            if (index > 0) {
                String field = expr.substring(0, index).trim();
                String expected = stripQuotes(expr.substring(index + operator.length()).trim());
                return compare(valueOf(row, field), operator, expected);
            }
        }
        int containsIndex = lower.indexOf(" contains ");
        if (containsIndex > 0) {
            String field = expr.substring(0, containsIndex).trim();
            String expected = stripQuotes(expr.substring(containsIndex + " contains ".length()).trim());
            Object actual = valueOf(row, field);
            return actual != null && String.valueOf(actual).contains(expected);
        }
        int notContainsIndex = lower.indexOf(" not contains ");
        if (notContainsIndex > 0) {
            String field = expr.substring(0, notContainsIndex).trim();
            String expected = stripQuotes(expr.substring(notContainsIndex + " not contains ".length()).trim());
            Object actual = valueOf(row, field);
            return actual == null || !String.valueOf(actual).contains(expected);
        }
        int inIndex = lower.indexOf(" in ");
        if (inIndex > 0) {
            String field = expr.substring(0, inIndex).trim();
            String valuesPart = expr.substring(inIndex + 4).trim();
            if (valuesPart.startsWith("(") && valuesPart.endsWith(")")) {
                valuesPart = valuesPart.substring(1, valuesPart.length() - 1);
            }
            Object actual = valueOf(row, field);
            List<String> candidates = splitValues(valuesPart);
            String actualText = actual == null ? null : String.valueOf(actual);
            for (String candidate : candidates) {
                if (candidate.equals(actualText)) {
                    return true;
                }
            }
            return false;
        }
        // 仅字段名：按“非空即真”处理
        Object value = valueOf(row, expr);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && !String.valueOf(value).isBlank();
    }

    /** 查找运算符位置，忽略被引号包裹的内容。 */
    private static int indexOfOperator(String expr, String operator) {
        boolean inQuotes = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\'') {
                inQuotes = !inQuotes;
                continue;
            }
            if (inQuotes) {
                continue;
            }
            if (expr.startsWith(operator, i)) {
                // 避免把 ">=" 里的 ">" 误判为独立运算符
                if (operator.equals(">") && i + 1 < expr.length() && expr.charAt(i + 1) == '=') {
                    continue;
                }
                if (operator.equals("<") && i + 1 < expr.length() && (expr.charAt(i + 1) == '=' || expr.charAt(i + 1) == '>')) {
                    continue;
                }
                if (operator.equals("=") && (i + 1 < expr.length() && expr.charAt(i + 1) == '=')
                        || (i > 0 && (expr.charAt(i - 1) == '!' || expr.charAt(i - 1) == '<' || expr.charAt(i - 1) == '>'))) {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    private static boolean compare(Object actual, String operator, String expected) {
        if (actual == null) {
            return operator.equals("!=") || operator.equals("<>");
        }
        BigDecimal actualNumber = toNumber(actual);
        BigDecimal expectedNumber = toNumber(expected);
        if (actualNumber != null && expectedNumber != null) {
            int cmp = actualNumber.compareTo(expectedNumber);
            return switch (operator) {
                case "=", "==" -> cmp == 0;
                case "!=", "<>" -> cmp != 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> false;
            };
        }
        String actualText = String.valueOf(actual);
        int cmp = actualText.compareTo(expected);
        return switch (operator) {
            case "=", "==" -> actualText.equals(expected);
            case "!=", "<>" -> !actualText.equals(expected);
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    private static BigDecimal toNumber(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> splitValues(String text) {
        List<String> values = new ArrayList<>();
        for (String item : text.split(",")) {
            values.add(stripQuotes(item.trim()));
        }
        return values;
    }

    private static String stripQuotes(String text) {
        if (text.length() >= 2 && ((text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("\"") && text.endsWith("\"")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 大小写不敏感取字段值（源端列名可能被库转成小写）。 */
    private static Object valueOf(Map<String, Object> row, String field) {
        if (row == null) {
            return null;
        }
        if (row.containsKey(field)) {
            return row.get(field);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(field)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
