package com.share.catalog.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 字典与枚举文案（状态/共享属性/密级/维度），供接口输出与前端下拉复用。 */
public final class Dicts {

    private Dicts() {
    }

    /** 数据源状态 */
    public static final Map<String, String> SOURCE_STATUS = new LinkedHashMap<>();
    /** 目录状态 */
    public static final Map<String, String> CATALOG_STATUS = new LinkedHashMap<>();
    /** 共享属性 */
    public static final Map<String, String> SHARE_TYPE = new LinkedHashMap<>();
    /** 密级 */
    public static final Map<Integer, String> SECRET_LEVEL = new LinkedHashMap<>();
    /** 分类维度 */
    public static final Map<String, String> DIMENSION = new LinkedHashMap<>();

    static {
        SOURCE_STATUS.put("ENABLED", "启用");
        SOURCE_STATUS.put("DISABLED", "停用");

        CATALOG_STATUS.put("DRAFT", "草稿");
        CATALOG_STATUS.put("PENDING", "待审核");
        CATALOG_STATUS.put("PUBLISHED", "已发布");
        CATALOG_STATUS.put("REJECTED", "已驳回");
        CATALOG_STATUS.put("OFFLINE", "已下架");

        SHARE_TYPE.put("UNCONDITIONAL", "无条件共享");
        SHARE_TYPE.put("CONDITIONAL", "有条件共享");
        SHARE_TYPE.put("NO_SHARE", "不予共享");

        SECRET_LEVEL.put(1, "1级（公开）");
        SECRET_LEVEL.put(2, "2级（内部）");
        SECRET_LEVEL.put(3, "3级（敏感）");
        SECRET_LEVEL.put(4, "4级（核心）");

        DIMENSION.put("SUBJECT", "主题分类");
        DIMENSION.put("DEPT", "部门分类");
        DIMENSION.put("DOMAIN", "业务领域");
    }

    public static String sourceStatusText(String code) {
        return code == null ? null : SOURCE_STATUS.getOrDefault(code, code);
    }

    public static String catalogStatusText(String code) {
        return code == null ? null : CATALOG_STATUS.getOrDefault(code, code);
    }

    public static String shareTypeText(String code) {
        return code == null ? null : SHARE_TYPE.getOrDefault(code, code);
    }

    public static String secretLevelText(Integer level) {
        return level == null ? null : SECRET_LEVEL.getOrDefault(level, level + "级");
    }

    public static String dimensionText(String code) {
        return code == null ? null : DIMENSION.getOrDefault(code, code);
    }

    /** 字典转下拉列表：[{code,label}]。 */
    public static List<Map<String, Object>> options(Map<String, String> dict) {
        List<Map<String, Object>> list = new ArrayList<>();
        dict.forEach((code, label) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("label", label);
            list.add(item);
        });
        return list;
    }
}
