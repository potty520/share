package com.share.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 样例数据预览（FR-03：挂接后可在线预览样例数据，敏感字段按脱敏规则处理）。 */
@Getter
@Setter
public class PreviewVO {

    private Long catalogId;
    private String resourceCode;
    private String resourceName;
    private Long sourceId;
    private String sourceType;
    private String tableName;
    private String filePath;
    private String apiUrl;
    /** 预览数据来源：TABLE（挂接库表）/ SAMPLE（内置样例数据） */
    private String source;
    /** 说明信息（如库表不存在时的降级原因） */
    private String message;
    private int limit;
    /** 列定义 */
    private List<PreviewColumn> columns = new ArrayList<>();
    /** 数据行 */
    private List<Map<String, Object>> rows = new ArrayList<>();
    /** 已脱敏字段名 */
    private List<String> maskedFields = new ArrayList<>();
    private LocalDateTime generatedAt;

    /** 预览列定义。 */
    @Getter
    @Setter
    public static class PreviewColumn {
        private String fieldName;
        private String fieldType;
        private String fieldComment;
        private boolean sensitive;
        private String maskRuleCode;
    }

    /** 便捷方法：构造一行数据。 */
    public static Map<String, Object> newRow() {
        return new LinkedHashMap<>();
    }
}
