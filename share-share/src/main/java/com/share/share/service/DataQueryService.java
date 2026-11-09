package com.share.share.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.share.entity.ShareApi;
import com.share.share.entity.ShareApiParam;
import com.share.share.repository.ShareApiParamRepository;
import com.share.share.support.ShareUtils;
import com.share.share.support.TableMetaCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Clob;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 共享 API 数据查询引擎（FR-07）。
 * <p>按 API 定义的挂接表、参数白名单、返回字段真实查询平台库：
 * <ul>
 *   <li>表名/列名做标识符白名单校验，参数只允许 API 定义过的字段，杜绝 SQL 注入；</li>
 *   <li>支持分页（pageNum/pageSize）与自定义参数过滤（eq/ne/like/ge/le/gt/lt）；</li>
 *   <li>返回字段按声明顺序过滤，脱敏字段交由 {@link MaskingHelper} 处理。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQueryService {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private final JdbcTemplate jdbcTemplate;
    private final TableMetaCache tableMetaCache;
    private final ShareApiParamRepository paramRepository;
    private final MaskingHelper maskingHelper;

    /** 查询结果。 */
    public record QueryResult(int pageNum, int pageSize, long total, List<Map<String, Object>> rows,
                              List<String> maskedFields, long costMs) {
    }

    /** 参数定义（按排序号）。 */
    public List<ShareApiParam> paramsOf(Long apiId) {
        return paramRepository.findByApiIdOrderBySortNoAscIdAsc(apiId);
    }

    /**
     * 执行查询。
     *
     * @param api      共享 API
     * @param filters  过滤参数（参数名 → 值）
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param mask     是否脱敏
     */
    public QueryResult query(ShareApi api, Map<String, Object> filters, int pageNum, int pageSize, boolean mask) {
        long start = System.currentTimeMillis();
        int num = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        String table = api.getSourceTable();
        if (table == null || table.isBlank()) {
            throw new BizException(ErrorCode.BUSINESS, "共享服务未配置挂接数据表");
        }
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "挂接表名不合法: " + table);
        }
        List<String> columns = tableMetaCache.columns(table);
        if (columns.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS, "挂接数据表不存在或不可读: " + table);
        }

        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        List<ShareApiParam> defs = paramsOf(api.getId());
        Map<String, Object> safeFilters = filters == null ? Map.of() : filters;
        for (ShareApiParam def : defs) {
            Object raw = pickFilter(safeFilters, def.getParamName());
            boolean blank = raw == null || String.valueOf(raw).isBlank();
            if (blank) {
                if (Boolean.TRUE.equals(def.getRequired())) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "缺少必填参数: " + def.getParamName());
                }
                continue;
            }
            String column = resolveColumn(columns, def.getColumnName() == null || def.getColumnName().isBlank()
                    ? def.getParamName() : def.getColumnName());
            if (column == null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "参数 " + def.getParamName() + " 在挂接表中无对应列");
            }
            appendCondition(where, args, column, def.getOp(), raw, def.getParamType());
        }

        String orderColumn = columns.stream()
                .filter(c -> "id".equalsIgnoreCase(c))
                .findFirst()
                .orElse(columns.get(0));
        String baseSql = "select * from " + table + where;
        long total;
        try {
            Long counted = jdbcTemplate.queryForObject("select count(*) from " + table + where,
                    Long.class, args.toArray());
            total = counted == null ? 0L : counted;
        } catch (Exception e) {
            log.warn("共享服务统计总数失败 api={} err={}", api.getApiCode(), e.getMessage());
            throw new BizException(ErrorCode.BUSINESS, "查询失败: " + e.getMessage());
        }
        String sql = baseSql + " order by " + orderColumn + " asc limit " + size + " offset " + ((long) (num - 1) * size);
        List<Map<String, Object>> rawRows;
        try {
            rawRows = jdbcTemplate.queryForList(sql, args.toArray());
        } catch (Exception e) {
            log.warn("共享服务查询数据失败 api={} sql={} err={}", api.getApiCode(), sql, e.getMessage());
            throw new BizException(ErrorCode.BUSINESS, "查询失败: " + e.getMessage());
        }

        List<String> returnFields = ShareUtils.split(api.getReturnFields());
        List<String> maskFields = ShareUtils.split(api.getMaskFields());
        List<Map<String, Object>> rows = new ArrayList<>(rawRows.size());
        List<String> maskedFields = new ArrayList<>();
        for (Map<String, Object> rawRow : rawRows) {
            Map<String, Object> row = project(rawRow, returnFields);
            if (mask) {
                List<String> masked = maskingHelper.maskRow(row, maskFields);
                for (String f : masked) {
                    if (!maskedFields.contains(f)) {
                        maskedFields.add(f);
                    }
                }
            }
            rows.add(row);
        }
        return new QueryResult(num, size, total, rows, maskedFields, System.currentTimeMillis() - start);
    }

    /** 按返回字段声明投影并做类型归一（Timestamp/Date/Clob → Java 时间/字符串）。 */
    private Map<String, Object> project(Map<String, Object> rawRow, List<String> returnFields) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (returnFields.isEmpty()) {
            rawRow.forEach((k, v) -> out.put(toCamel(k), normalizeValue(v)));
            return out;
        }
        for (String field : returnFields) {
            Object value = ShareUtils.pickIgnoreCase(rawRow, field);
            out.put(field, normalizeValue(value));
        }
        return out;
    }

    /** 追加过滤条件。 */
    private void appendCondition(StringBuilder where, List<Object> args, String column, String op,
                                 Object raw, String paramType) {
        String operator = op == null || op.isBlank() ? "eq" : op.trim().toLowerCase(Locale.ROOT);
        String value = String.valueOf(raw).trim();
        switch (operator) {
            case "like" -> {
                where.append(" and ").append(column).append(" like ?");
                args.add("%" + value + "%");
            }
            case "ne" -> {
                where.append(" and ").append(column).append(" <> ?");
                args.add(convert(value, paramType));
            }
            case "ge" -> {
                where.append(" and ").append(column).append(" >= ?");
                args.add(convert(value, paramType));
            }
            case "le" -> {
                where.append(" and ").append(column).append(" <= ?");
                args.add(convert(value, paramType));
            }
            case "gt" -> {
                where.append(" and ").append(column).append(" > ?");
                args.add(convert(value, paramType));
            }
            case "lt" -> {
                where.append(" and ").append(column).append(" < ?");
                args.add(convert(value, paramType));
            }
            default -> {
                where.append(" and ").append(column).append(" = ?");
                args.add(convert(value, paramType));
            }
        }
    }

    /** 按参数类型转换过滤值，类型不合法直接报错（避免隐式转换导致全表扫描/异常）。 */
    private Object convert(String value, String paramType) {
        if (paramType == null || paramType.isBlank()) {
            return value;
        }
        try {
            return switch (paramType.trim().toLowerCase(Locale.ROOT)) {
                case "int", "integer" -> Integer.valueOf(value);
                case "long", "bigint" -> Long.valueOf(value);
                case "decimal", "double", "number" -> new java.math.BigDecimal(value);
                case "bool", "boolean" -> Boolean.valueOf(value);
                case "date" -> java.sql.Date.valueOf(java.time.LocalDate.parse(value));
                case "datetime", "time" -> java.sql.Timestamp.valueOf(value.replace("T", " "));
                default -> value;
            };
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数值类型不正确: " + value);
        }
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) Math.min(clob.length(), 4000));
            } catch (Exception e) {
                return null;
            }
        }
        return value;
    }

    private Object pickFilter(Map<String, Object> filters, String name) {
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            if (ShareUtils.normalizeKey(e.getKey()).equals(ShareUtils.normalizeKey(name))) {
                return e.getValue();
            }
        }
        return null;
    }

    private String resolveColumn(List<String> columns, String name) {
        String target = ShareUtils.normalizeKey(name);
        for (String column : columns) {
            if (ShareUtils.normalizeKey(column).equals(target)) {
                return column;
            }
        }
        return null;
    }

    private String toCamel(String column) {
        if (column == null || column.isEmpty()) {
            return column;
        }
        if (!column.contains("_")) {
            return column.toLowerCase(Locale.ROOT);
        }
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : column.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
