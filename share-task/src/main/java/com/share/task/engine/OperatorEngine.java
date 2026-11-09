package com.share.task.engine;

import com.share.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 加工算子执行引擎（FR-05）：去重、空值填充、日期标准化、手机号校验、编码翻译、
 * 字段拆分合并、条件过滤、类型转换、正则替换、常量补充、脱敏、计算字段等。
 * <p>每个算子作用在 {@code List<Map>} 数据集上，多个算子按顺序串成算子链；
 * 无共享状态，可被多个任务实例并发调用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorEngine {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final MaskHelper maskHelper;

    /** 算子执行结果：新数据集 + 受影响行数。 */
    private record OpResult(List<Map<String, Object>> rows, int affected) {
    }

    /**
     * 顺序执行算子链。
     *
     * @param input     输入数据行
     * @param operators 算子链
     * @param stats     输出统计（每个算子的影响行数）
     * @return 处理后的数据行
     */
    public List<Map<String, Object>> apply(List<Map<String, Object>> input,
                                           List<Map<String, Object>> operators,
                                           List<Map<String, Object>> stats) {
        List<Map<String, Object>> current = new ArrayList<>();
        for (Map<String, Object> row : input) {
            current.add(new LinkedHashMap<>(row));
        }
        if (operators == null || operators.isEmpty()) {
            return current;
        }
        for (Map<String, Object> operator : operators) {
            if (operator == null) {
                continue;
            }
            String type = stringValue(operator.get("type"));
            if (type == null || type.isBlank()) {
                continue;
            }
            Map<String, Object> params = paramsOf(operator);
            int before = current.size();
            OpResult result;
            try {
                result = applyOne(type.toUpperCase(), params, current);
            } catch (Exception e) {
                log.warn("算子 {} 执行异常，跳过：{}", type, e.getMessage());
                result = new OpResult(current, 0);
            }
            current = result.rows();
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("type", type.toUpperCase());
            stat.put("affected", result.affected());
            stat.put("rowsBefore", before);
            stat.put("rowsAfter", current.size());
            stats.add(stat);
        }
        return current;
    }

    /** 执行单个算子，返回新数据集与受影响行数。 */
    private OpResult applyOne(String type, Map<String, Object> params, List<Map<String, Object>> rows) {
        int affected = 0;
        List<Map<String, Object>> resultRows = rows;
        switch (type) {
            case "DEDUP" -> {
                List<String> fields = fields(params);
                boolean keepLast = "last".equalsIgnoreCase(stringValue(params.get("keep")));
                Set<String> seen = new HashSet<>();
                List<Map<String, Object>> kept = new ArrayList<>();
                List<Map<String, Object>> source = new ArrayList<>(rows);
                if (keepLast) {
                    java.util.Collections.reverse(source);
                }
                for (Map<String, Object> row : source) {
                    if (seen.add(dedupKey(row, fields))) {
                        kept.add(row);
                    } else {
                        affected++;
                    }
                }
                if (keepLast) {
                    java.util.Collections.reverse(kept);
                }
                resultRows = kept;
            }
            case "FILL_NULL" -> {
                List<String> fields = fields(params);
                String fill = stringValue(params.get("value"));
                if (fill == null || fill.isBlank()) {
                    fill = "未知";
                }
                for (Map<String, Object> row : rows) {
                    for (String field : fields) {
                        Object value = valueOf(row, field);
                        if (value == null || String.valueOf(value).isBlank()) {
                            putValue(row, field, fill);
                            affected++;
                        }
                    }
                }
            }
            case "TRIM" -> {
                List<String> fields = fields(params);
                for (Map<String, Object> row : rows) {
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        if (!fields.isEmpty() && fields.stream().noneMatch(f -> f.equalsIgnoreCase(entry.getKey()))) {
                            continue;
                        }
                        if (entry.getValue() instanceof String text) {
                            String trimmed = text.trim();
                            if (!trimmed.equals(text)) {
                                entry.setValue(trimmed);
                                affected++;
                            }
                        }
                    }
                }
            }
            case "NORMALIZE_DATE" -> {
                List<String> fields = fields(params);
                for (Map<String, Object> row : rows) {
                    for (String field : fields) {
                        Object value = valueOf(row, field);
                        if (value == null) {
                            continue;
                        }
                        Object normalized = DateHelper.normalize(value);
                        if (!String.valueOf(normalized).equals(String.valueOf(value))) {
                            putValue(row, field, normalized);
                            affected++;
                        }
                    }
                }
            }
            case "VALIDATE_PHONE" -> {
                List<String> fields = fields(params);
                boolean clear = "clear".equalsIgnoreCase(stringValue(params.get("action")));
                List<Map<String, Object>> kept = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    boolean valid = true;
                    for (String field : fields) {
                        Object value = valueOf(row, field);
                        if (value != null && !String.valueOf(value).isBlank()
                                && !PHONE_PATTERN.matcher(String.valueOf(value).trim()).matches()) {
                            valid = false;
                            if (clear) {
                                putValue(row, field, null);
                            }
                        }
                    }
                    if (valid || clear) {
                        kept.add(row);
                    } else {
                        affected++;
                    }
                }
                resultRows = kept;
            }
            case "CODE_TRANSLATE" -> {
                String field = stringValue(params.get("field"));
                Map<String, Object> mapping = mapValue(params.get("mapping") != null ? params.get("mapping") : params.get("codeMap"));
                String defaultValue = stringValue(params.get("defaultValue"));
                if (field != null && mapping != null) {
                    for (Map<String, Object> row : rows) {
                        Object value = valueOf(row, field);
                        String key = value == null ? null : String.valueOf(value);
                        if (key != null && mapping.containsKey(key)) {
                            putValue(row, field, mapping.get(key));
                            affected++;
                        } else if (defaultValue != null) {
                            putValue(row, field, defaultValue);
                            affected++;
                        }
                    }
                }
            }
            case "SPLIT_FIELD" -> {
                String field = stringValue(params.get("field"));
                String separator = stringValue(params.get("separator"));
                if (separator == null) {
                    separator = ",";
                }
                boolean trim = !"false".equalsIgnoreCase(stringValue(params.get("trim")));
                List<String> targets = stringList(params.get("targets"));
                if (field != null && !targets.isEmpty()) {
                    String finalSeparator = separator;
                    for (Map<String, Object> row : rows) {
                        Object value = valueOf(row, field);
                        String text = value == null ? "" : String.valueOf(value);
                        String[] parts = text.split(Pattern.quote(finalSeparator), -1);
                        for (int i = 0; i < targets.size(); i++) {
                            String part = i < parts.length ? parts[i] : null;
                            putValue(row, targets.get(i), part != null && trim ? part.trim() : part);
                        }
                        affected++;
                    }
                }
            }
            case "MERGE_FIELD" -> {
                List<String> sources = stringList(params.get("sources"));
                String target = stringValue(params.get("target"));
                String separator = stringValue(params.get("separator"));
                if (separator == null) {
                    separator = "-";
                }
                if (target != null && !sources.isEmpty()) {
                    String finalSeparator = separator;
                    for (Map<String, Object> row : rows) {
                        StringBuilder sb = new StringBuilder();
                        for (String source : sources) {
                            Object value = valueOf(row, source);
                            if (value == null) {
                                continue;
                            }
                            if (sb.length() > 0) {
                                sb.append(finalSeparator);
                            }
                            sb.append(value);
                        }
                        putValue(row, target, sb.toString());
                        affected++;
                    }
                }
            }
            case "FILTER" -> {
                String condition = stringValue(params.get("condition"));
                if (condition != null && !condition.isBlank()) {
                    List<Map<String, Object>> kept = new ArrayList<>();
                    for (Map<String, Object> row : rows) {
                        if (ExpressionEvaluator.test(condition, row)) {
                            kept.add(row);
                        } else {
                            affected++;
                        }
                    }
                    resultRows = kept;
                }
            }
            case "CONVERT_TYPE" -> {
                String targetType = stringValue(params.get("targetType"));
                List<String> fields = fields(params);
                for (Map<String, Object> row : rows) {
                    for (String field : fields) {
                        Object value = valueOf(row, field);
                        if (value == null || String.valueOf(value).isBlank()) {
                            continue;
                        }
                        try {
                            Object converted = convertType(String.valueOf(value).trim(), targetType);
                            if (converted != null) {
                                putValue(row, field, converted);
                                affected++;
                            }
                        } catch (Exception ignored) {
                            // 单值转换失败不影响整批
                        }
                    }
                }
            }
            case "REGEX_REPLACE" -> {
                String field = stringValue(params.get("field"));
                String pattern = stringValue(params.get("pattern"));
                String replacement = stringValue(params.get("replacement"));
                if (replacement == null) {
                    replacement = "";
                }
                if (field != null && pattern != null) {
                    Pattern compiled = Pattern.compile(pattern);
                    for (Map<String, Object> row : rows) {
                        Object value = valueOf(row, field);
                        if (value != null) {
                            String replaced = compiled.matcher(String.valueOf(value)).replaceAll(replacement);
                            if (!replaced.equals(String.valueOf(value))) {
                                putValue(row, field, replaced);
                                affected++;
                            }
                        }
                    }
                }
            }
            case "ADD_CONST" -> {
                String field = stringValue(params.get("field"));
                Object value = params.get("value");
                if (field != null) {
                    for (Map<String, Object> row : rows) {
                        putValue(row, field, value);
                        affected++;
                    }
                }
            }
            case "RENAME" -> {
                String from = stringValue(params.get("from"));
                String to = stringValue(params.get("to"));
                if (from != null && to != null) {
                    for (Map<String, Object> row : rows) {
                        Object value = row.remove(from);
                        if (value != null) {
                            row.put(to, value);
                            affected++;
                        }
                    }
                }
            }
            case "LIMIT" -> {
                Integer max = intValue(params.get("max"));
                if (max != null && max >= 0 && rows.size() > max) {
                    resultRows = new ArrayList<>(rows.subList(0, max));
                    affected = rows.size() - max;
                }
            }
            case "MASK" -> {
                List<String> targetFields = fields(params);
                String maskType = stringValue(params.get("maskType"));
                String maskParam = stringValue(params.get("maskParam"));
                for (Map<String, Object> row : rows) {
                    for (String field : targetFields) {
                        Object value = valueOf(row, field);
                        if (value == null) {
                            continue;
                        }
                        String masked = maskHelper.mask(maskType, String.valueOf(value), maskParam);
                        if (masked != null && !masked.equals(String.valueOf(value))) {
                            putValue(row, field, masked);
                            affected++;
                        }
                    }
                }
            }
            case "CALC_FIELD" -> {
                String target = stringValue(params.get("target"));
                String expression = stringValue(params.get("expression"));
                if (target != null && expression != null) {
                    for (Map<String, Object> row : rows) {
                        BigDecimal value = ArithmeticEvaluator.evaluate(expression, row);
                        if (value != null) {
                            putValue(row, target, value.stripTrailingZeros().toPlainString());
                            affected++;
                        }
                    }
                }
            }
            default -> log.warn("未知算子类型：{}，已跳过", type);
        }
        return new OpResult(resultRows, affected);
    }

    private Object convertType(String value, String targetType) {
        if (targetType == null) {
            return value;
        }
        return switch (targetType.toUpperCase()) {
            case "INT", "LONG", "INTEGER" -> Long.parseLong(value.replaceAll("\\.0+$", ""));
            case "DECIMAL", "DOUBLE", "BIGDECIMAL" -> new BigDecimal(value);
            case "STRING", "VARCHAR" -> value;
            default -> value;
        };
    }

    private String dedupKey(Map<String, Object> row, List<String> fields) {
        if (fields.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            Object value = valueOf(row, field);
            sb.append(value == null ? "" : String.valueOf(value).trim()).append('\u0001');
        }
        return sb.toString();
    }

    private Map<String, Object> paramsOf(Map<String, Object> operator) {
        Object params = operator.get("params");
        if (params instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return operator;
    }

    private List<String> fields(Map<String, Object> params) {
        Object fields = params.get("fields");
        if (fields == null) {
            fields = params.get("field");
        }
        return stringList(fields);
    }

    private List<String> stringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value == null) {
            return list;
        }
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    list.add(String.valueOf(item).trim());
                }
            }
            return list;
        }
        for (String item : String.valueOf(value).split(",")) {
            if (!item.isBlank()) {
                list.add(item.trim());
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return JsonUtil.fromJson(text, Map.class);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object valueOf(Map<String, Object> row, String field) {
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

    private void putValue(Map<String, Object> row, String field, Object value) {
        String actualKey = field;
        if (!row.containsKey(field)) {
            for (String key : row.keySet()) {
                if (key.equalsIgnoreCase(field)) {
                    actualKey = key;
                    break;
                }
            }
        }
        row.put(actualKey, value);
    }
}
