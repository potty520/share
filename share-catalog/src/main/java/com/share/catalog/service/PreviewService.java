package com.share.catalog.service;

import com.share.catalog.dto.PreviewVO;
import com.share.catalog.entity.CatalogItem;
import com.share.catalog.entity.CatalogResource;
import com.share.catalog.repository.CatalogItemRepository;
import com.share.catalog.support.MaskRuleResolver;
import com.share.common.spi.MaskService;
import com.share.common.spi.MaskType;
import com.share.common.util.IdGen;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 目录样例数据预览（FR-03：挂接后在线预览样例数据）。
 * <p>挂接了库表且表存在时，用 JdbcTemplate 取前 N 行真实数据；否则退回编目时录入的样例数据。
 * 敏感字段统一经 {@link MaskService} 脱敏（share-security 提供 @Primary 实现，缺省为恒等实现时
 * 使用本地算法兜底，保证预览链路不会泄露原始值）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewService {

    /** 表名白名单：只允许字母数字下划线，避免 SQL 注入 */
    private static final Pattern TABLE_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,128}$");
    /** 预览默认行数 */
    public static final int DEFAULT_LIMIT = 10;
    /** 预览最大行数 */
    public static final int MAX_LIMIT = 50;

    private final JdbcTemplate jdbcTemplate;
    private final CatalogItemRepository itemRepository;
    private final MaskRuleResolver maskRuleResolver;
    /** 脱敏实现可能来自 share-security（@Primary）与 share-common 的兜底实现，按列表注入更稳健 */
    private final List<MaskService> maskServices;

    public PreviewVO preview(CatalogResource resource, int limit) {
        int rows = normalizeLimit(limit);
        List<CatalogItem> items = itemRepository
                .findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(resource.getId());

        PreviewVO vo = new PreviewVO();
        vo.setCatalogId(resource.getId());
        vo.setResourceCode(resource.getResourceCode());
        vo.setResourceName(resource.getResourceName());
        vo.setSourceId(resource.getSourceId());
        vo.setSourceType(resource.getSourceType());
        vo.setTableName(resource.getTableName());
        vo.setFilePath(resource.getFilePath());
        vo.setApiUrl(resource.getApiUrl());
        vo.setLimit(rows);
        vo.setGeneratedAt(LocalDateTime.now());
        vo.setColumns(buildColumns(items));

        List<Map<String, Object>> rawRows = null;
        String source = "SAMPLE";
        String message;
        String tableName = resource.getTableName();
        if (tableName != null && !tableName.isBlank()) {
            if (!TABLE_PATTERN.matcher(tableName.trim()).matches()) {
                message = "挂接表名不合法，已返回内置样例数据";
            } else {
                try {
                    rawRows = jdbcTemplate.queryForList("select * from " + tableName.trim() + " limit " + rows);
                    source = "TABLE";
                    message = "已从挂接表 " + tableName.trim() + " 读取前 " + rawRows.size() + " 行";
                } catch (Exception e) {
                    log.debug("预览挂接表 {} 失败: {}", tableName, e.getMessage());
                    message = "挂接表 " + tableName.trim() + " 暂不可查询（" + brief(e) + "），已返回内置样例数据";
                }
            }
        } else if (resource.getFilePath() != null && !resource.getFilePath().isBlank()) {
            message = "文件类挂接（" + resource.getFilePath() + "）不在线解析，返回内置样例数据";
        } else if (resource.getApiUrl() != null && !resource.getApiUrl().isBlank()) {
            message = "接口类挂接（" + resource.getApiUrl() + "）需授权后调用，返回内置样例数据";
        } else {
            message = "该目录尚未挂接物理数据，返回内置样例数据";
        }
        if (rawRows == null) {
            rawRows = buildSampleRows(items, Math.min(rows, 3));
        }

        Set<String> maskedFields = new LinkedHashSet<>();
        List<Map<String, Object>> resultRows = new ArrayList<>();
        for (Map<String, Object> raw : rawRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                CatalogItem item = findItem(items, entry.getKey());
                String column = item != null ? item.getFieldName() : entry.getKey();
                Object value = entry.getValue();
                if (item != null && Boolean.TRUE.equals(item.getSensitive()) && value != null) {
                    value = applyMask(item, String.valueOf(value));
                    maskedFields.add(column);
                }
                row.put(column, value);
            }
            resultRows.add(row);
        }

        vo.setSource(source);
        vo.setMessage(message);
        vo.setRows(resultRows);
        vo.setMaskedFields(new ArrayList<>(maskedFields));
        return vo;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private List<PreviewVO.PreviewColumn> buildColumns(List<CatalogItem> items) {
        List<PreviewVO.PreviewColumn> columns = new ArrayList<>();
        for (CatalogItem item : items) {
            PreviewVO.PreviewColumn column = new PreviewVO.PreviewColumn();
            column.setFieldName(item.getFieldName());
            column.setFieldType(item.getFieldType());
            column.setFieldComment(item.getFieldComment());
            column.setSensitive(Boolean.TRUE.equals(item.getSensitive()));
            column.setMaskRuleCode(item.getMaskRuleCode());
            columns.add(column);
        }
        return columns;
    }

    /** 内置样例数据：按编目录入的 sampleValue 生成，敏感字段同样脱敏。 */
    private List<Map<String, Object>> buildSampleRows(List<CatalogItem> items, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int rowCount = Math.max(1, count);
        for (int i = 0; i < rowCount; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (CatalogItem item : items) {
                String value = item.getSampleValue();
                if (value == null || value.isBlank()) {
                    value = "示例" + (item.getFieldComment() == null ? "" : item.getFieldComment()) + (i + 1);
                }
                if (Boolean.TRUE.equals(item.getSensitive())) {
                    value = applyMask(item, value);
                }
                row.put(item.getFieldName(), value);
            }
            rows.add(row);
        }
        return rows;
    }

    private CatalogItem findItem(List<CatalogItem> items, String column) {
        if (column == null) {
            return null;
        }
        for (CatalogItem item : items) {
            if (column.equalsIgnoreCase(item.getFieldName())) {
                return item;
            }
        }
        return null;
    }

    /** 脱敏：优先调用 share-security 的 MaskService，返回原值或异常时使用本地算法兜底。 */
    private String applyMask(CatalogItem item, String value) {
        if (value == null) {
            return null;
        }
        MaskType type = maskRuleResolver.typeOf(item);
        String param = maskRuleResolver.paramOf(item);
        MaskService maskService = resolveMaskService();
        if (maskService != null) {
            try {
                String masked = maskService.mask(type, value, param);
                if (masked != null && !masked.equals(value)) {
                    return masked;
                }
            } catch (Exception e) {
                log.debug("调用 MaskService 失败，使用本地脱敏兜底: {}", e.getMessage());
            }
        }
        return localMask(type, value, param);
    }

    /** 选取实际脱敏实现：跳过 share-common 的恒等兜底实现。 */
    private MaskService resolveMaskService() {
        if (maskServices == null || maskServices.isEmpty()) {
            return null;
        }
        for (MaskService candidate : maskServices) {
            if (!(candidate instanceof com.share.common.spi.DefaultMaskService)) {
                return candidate;
            }
        }
        return maskServices.get(0);
    }

    /** 本地脱敏兜底实现（与 share-security 的算法语义保持一致）。 */
    private String localMask(MaskType type, String value, String param) {
        if (value == null) {
            return null;
        }
        switch (type) {
            case NONE:
                return value;
            case REPLACE:
                return param == null || param.isBlank() ? "***" : param;
            case HASH:
                return IdGen.md5(value).substring(0, 16);
            case ENCRYPT:
            case PERTURB:
                return IdGen.sha256(value).substring(0, 20);
            case GENERALIZE: {
                int keep = parseFirst(param, 6);
                if (value.length() <= keep) {
                    return value;
                }
                return value.substring(0, Math.max(1, keep)) + "***";
            }
            case MASK:
            default: {
                int[] range = parseRange(param, 3, 4);
                return maskKeep(value, range[0], range[1]);
            }
        }
    }

    private String maskKeep(String value, int prefix, int suffix) {
        int len = value.length();
        if (len <= prefix + suffix) {
            return value.substring(0, Math.min(1, len)) + "*".repeat(Math.max(3, len - 1));
        }
        int hidden = len - prefix - suffix;
        return value.substring(0, prefix) + "*".repeat(Math.max(4, hidden)) + value.substring(len - suffix);
    }

    private int[] parseRange(String param, int defaultPrefix, int defaultSuffix) {
        if (param == null || param.isBlank()) {
            return new int[]{defaultPrefix, defaultSuffix};
        }
        String[] parts = param.split(",");
        int prefix = parseInt(parts[0].trim(), defaultPrefix);
        int suffix = parts.length > 1 ? parseInt(parts[1].trim(), defaultSuffix) : defaultSuffix;
        return new int[]{Math.max(0, prefix), Math.max(0, suffix)};
    }

    private int parseFirst(String param, int defaultValue) {
        if (param == null || param.isBlank()) {
            return defaultValue;
        }
        return parseInt(param.split(",")[0].trim(), defaultValue);
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String brief(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        msg = msg.replaceAll("\\s+", " ");
        return msg.length() > 80 ? msg.substring(0, 80) + "…" : msg;
    }
}
