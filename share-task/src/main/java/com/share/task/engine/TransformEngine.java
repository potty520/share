package com.share.task.engine;

import com.share.common.util.JsonUtil;
import com.share.task.dto.MappingRule;
import com.share.task.dto.TransformResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据加工转换引擎（FR-04 字段映射 + FR-05 算子链 + FR-10 静态脱敏）：
 * 严格按“字段映射 → 过滤/算子链 → 静态脱敏”的顺序处理，输出目标端数据结构。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransformEngine {

    private final OperatorEngine operatorEngine;

    private final MaskHelper maskHelper;

    /**
     * 执行完整加工链路。
     *
     * @param sourceRows      源数据行
     * @param mapping         字段映射规则（为空表示原样透传）
     * @param operators       算子链
     * @param filterCondition 源端过滤条件
     */
    public TransformResult transform(List<Map<String, Object>> sourceRows,
                                     List<MappingRule> mapping,
                                     List<Map<String, Object>> operators,
                                     String filterCondition) {
        long start = System.currentTimeMillis();
        TransformResult result = new TransformResult();
        result.setInputRows(sourceRows == null ? 0 : sourceRows.size());

        boolean passThrough = mapping == null || mapping.isEmpty();
        List<Map<String, Object>> mapped = new ArrayList<>();
        if (sourceRows != null) {
            for (Map<String, Object> sourceRow : sourceRows) {
                LinkedHashMap<String, Object> targetRow = new LinkedHashMap<>();
                if (passThrough) {
                    targetRow.putAll(sourceRow);
                } else {
                    for (MappingRule rule : mapping) {
                        if (rule == null || rule.getTargetField() == null || rule.getTargetField().isBlank()) {
                            continue;
                        }
                        Object value = valueOf(sourceRow, rule.getSourceField());
                        targetRow.put(rule.getTargetField(), applyTransform(value, rule.getTransform()));
                    }
                }
                mapped.add(targetRow);
            }
        }

        // 源端过滤条件作为隐式 FILTER 算子（在算子链之前生效）
        List<Map<String, Object>> filtered = mapped;
        if (filterCondition != null && !filterCondition.isBlank()) {
            filtered = new ArrayList<>();
            for (Map<String, Object> row : mapped) {
                if (ExpressionEvaluator.test(filterCondition, row)) {
                    filtered.add(row);
                }
            }
        }

        // 算子链
        List<Map<String, Object>> operated = operatorEngine.apply(filtered, operators, result.getOperatorStats());

        // 静态脱敏（映射规则上的 maskType）
        if (mapping != null) {
            for (MappingRule rule : mapping) {
                if (rule == null || rule.getTargetField() == null || rule.getTargetField().isBlank()) {
                    continue;
                }
                if (rule.getMaskType() == null || rule.getMaskType().isBlank()
                        || "NONE".equalsIgnoreCase(rule.getMaskType().trim())) {
                    continue;
                }
                for (Map<String, Object> row : operated) {
                    Object value = row.get(rule.getTargetField());
                    if (value == null) {
                        continue;
                    }
                    String masked = maskHelper.mask(rule.getMaskType(), String.valueOf(value), rule.getMaskParam());
                    if (masked != null) {
                        row.put(rule.getTargetField(), masked);
                    }
                }
            }
        }

        result.setRows(operated);
        result.setFilteredRows(Math.max(0, result.getInputRows() - operated.size()));
        result.setColumns(resolveColumns(operated, mapping));
        result.setCostMs(System.currentTimeMillis() - start);
        return result;
    }

    /** 解析 mappingJson 字符串。 */
    public List<MappingRule> parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JsonUtil.fromJsonList(mappingJson, MappingRule.class);
        } catch (Exception e) {
            log.warn("映射规则解析失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 解析 operatorsJson 字符串。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parseOperators(String operatorsJson) {
        if (operatorsJson == null || operatorsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JsonUtil.fromJson(operatorsJson, List.class);
        } catch (Exception e) {
            log.warn("算子链解析失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 输出列：优先取首行真实列顺序，空结果时回退到映射目标字段。 */
    private List<String> resolveColumns(List<Map<String, Object>> rows, List<MappingRule> mapping) {
        if (!rows.isEmpty()) {
            return new ArrayList<>(rows.get(0).keySet());
        }
        List<String> columns = new ArrayList<>();
        if (mapping != null) {
            for (MappingRule rule : mapping) {
                if (rule != null && rule.getTargetField() != null && !rule.getTargetField().isBlank()) {
                    columns.add(rule.getTargetField());
                }
            }
        }
        return columns;
    }

    /** 应用单字段转换规则。 */
    private Object applyTransform(Object value, String transform) {
        if (transform == null || transform.isBlank() || "DIRECT".equalsIgnoreCase(transform.trim())
                || "NONE".equalsIgnoreCase(transform.trim())) {
            return value;
        }
        String rule = transform.trim();
        String upper = rule.toUpperCase();
        if (upper.startsWith("CONST:")) {
            return rule.substring("CONST:".length());
        }
        if (upper.startsWith("DEFAULT:")) {
            String fallback = rule.substring("DEFAULT:".length());
            return value == null || String.valueOf(value).isBlank() ? fallback : value;
        }
        if (upper.startsWith("SUBSTR:")) {
            String[] parts = rule.substring("SUBSTR:".length()).split(":");
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            try {
                int begin = Integer.parseInt(parts[0].trim());
                int length = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : text.length();
                int from = Math.min(Math.max(begin, 0), text.length());
                int to = Math.min(from + Math.max(length, 0), text.length());
                return text.substring(from, to);
            } catch (Exception e) {
                return value;
            }
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return switch (upper) {
            case "UPPER" -> text.toUpperCase();
            case "LOWER" -> text.toLowerCase();
            case "TRIM" -> text.trim();
            case "TO_DATE", "DATE" -> DateHelper.normalize(value);
            case "MASK_PHONE" -> maskHelper.mask("MASK", text, "3,4");
            case "MASK_IDCARD" -> maskHelper.mask("MASK", text, "4,4");
            case "TO_INT", "TO_LONG" -> {
                try {
                    yield Long.parseLong(text.trim().replaceAll("\\.0+$", ""));
                } catch (NumberFormatException e) {
                    yield value;
                }
            }
            case "TO_DECIMAL", "TO_NUMBER" -> {
                try {
                    yield new BigDecimal(text.trim());
                } catch (NumberFormatException e) {
                    yield value;
                }
            }
            case "TO_STRING" -> text;
            default -> value;
        };
    }

    /** 大小写不敏感取源字段值。 */
    private Object valueOf(Map<String, Object> row, String field) {
        if (field == null) {
            return null;
        }
        if (row.containsKey(field)) {
            return row.get(field);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(field.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
