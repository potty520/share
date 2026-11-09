package com.share.task.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加工算子字典（FR-05）：/api/task/v1/operators 返回给前端画布与试运行使用，
 * 字段级转换规则（transform）同样在此登记，保证前后端一致。
 */
public final class OperatorCatalog {

    private OperatorCatalog() {
    }

    /** 全部内置算子定义。 */
    public static List<Map<String, Object>> operators() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(operator("DEDUP", "去重", "清洗", "按指定字段去重，字段为空表示整行去重",
                param("fields", "array", false, "去重字段列表，为空表示整行"),
                param("keep", "string", false, "保留策略：first（默认）/ last")));
        list.add(operator("FILL_NULL", "空值填充", "清洗", "对空值/空串填充默认值",
                param("fields", "array", true, "字段列表"),
                param("value", "string", false, "填充值，默认“未知”")));
        list.add(operator("TRIM", "去首尾空格", "清洗", "去除字符串字段首尾空白",
                param("fields", "array", false, "字段列表，为空表示所有字符串字段")));
        list.add(operator("NORMALIZE_DATE", "日期标准化", "标准化", "把多种日期写法统一为 yyyy-MM-dd",
                param("fields", "array", true, "日期字段列表")));
        list.add(operator("VALIDATE_PHONE", "手机号校验", "校验", "校验手机号格式，非法行按策略剔除或置空",
                param("fields", "array", true, "手机号字段列表"),
                param("action", "string", false, "drop（默认剔除）/ clear（置空）")));
        list.add(operator("CODE_TRANSLATE", "编码翻译", "转换", "按代码表把代码值翻译为中文名称",
                param("field", "string", true, "待翻译字段"),
                param("mapping", "object", true, "代码映射表，如 {\"1\":\"男\",\"2\":\"女\"}"),
                param("defaultValue", "string", false, "未命中时的默认值")));
        list.add(operator("SPLIT_FIELD", "字段拆分", "转换", "按分隔符把一列拆成多列",
                param("field", "string", true, "源字段"),
                param("separator", "string", false, "分隔符，默认英文逗号"),
                param("targets", "array", true, "拆分后的目标字段列表"),
                param("trim", "boolean", false, "是否去空格，默认 true")));
        list.add(operator("MERGE_FIELD", "字段合并", "转换", "把多列拼接为一列",
                param("sources", "array", true, "源字段列表"),
                param("target", "string", true, "目标字段"),
                param("separator", "string", false, "连接符，默认“-”")));
        list.add(operator("FILTER", "条件过滤", "过滤", "按条件剔除不满足要求的行",
                param("condition", "string", true, "条件表达式，如 status = 'NORMAL'")));
        list.add(operator("CONVERT_TYPE", "类型转换", "转换", "按目标类型转换字段值",
                param("fields", "array", true, "字段列表"),
                param("targetType", "string", true, "目标类型：INT/LONG/DECIMAL/STRING")));
        list.add(operator("REGEX_REPLACE", "正则替换", "清洗", "按正则替换字段内容",
                param("field", "string", true, "字段"),
                param("pattern", "string", true, "正则表达式"),
                param("replacement", "string", false, "替换内容，默认空串")));
        list.add(operator("ADD_CONST", "常量补充", "转换", "为每行补充一个常量字段",
                param("field", "string", true, "目标字段"),
                param("value", "string", true, "常量值")));
        list.add(operator("RENAME", "字段改名", "转换", "重命名字段",
                param("from", "string", true, "原字段名"),
                param("to", "string", true, "新字段名")));
        list.add(operator("LIMIT", "行数限制", "过滤", "只保留前 N 行（抽样/试跑）",
                param("max", "integer", true, "最大行数")));
        list.add(operator("MASK", "静态脱敏", "安全", "调用脱敏服务对字段脱敏（MASK/REPLACE/HASH/GENERALIZE/PERTURB/ENCRYPT）",
                param("fields", "array", true, "字段列表"),
                param("maskType", "string", true, "脱敏算法类型"),
                param("maskParam", "string", false, "算法参数，如遮蔽保留前 3 后 4 传 3,4")));
        list.add(operator("CALC_FIELD", "计算字段", "转换", "按算术表达式计算新字段，如 salary * 12",
                param("target", "string", true, "目标字段"),
                param("expression", "string", true, "算术表达式，支持 + - * / 与括号")));
        return list;
    }

    /** 字段级转换规则字典。 */
    public static List<Map<String, Object>> transforms() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(transform("", "直接映射", "源字段值原样写入目标字段"));
        list.add(transform("UPPER", "转大写", "字符串转大写"));
        list.add(transform("LOWER", "转小写", "字符串转小写"));
        list.add(transform("TRIM", "去空格", "去除首尾空白"));
        list.add(transform("CONST", "常量", "固定常量，写法 CONST:启用"));
        list.add(transform("TO_DATE", "日期标准化", "任意日期写法 → yyyy-MM-dd"));
        list.add(transform("MASK_PHONE", "手机号遮蔽", "保留前 3 后 4，如 138****1234"));
        list.add(transform("MASK_IDCARD", "身份证遮蔽", "保留前 4 后 4"));
        list.add(transform("TO_INT", "转整数", "字符串转 Long"));
        list.add(transform("TO_DECIMAL", "转小数", "字符串转 BigDecimal"));
        list.add(transform("TO_STRING", "转字符串", "数值转字符串"));
        list.add(transform("DEFAULT", "空值默认", "空值填充，写法 DEFAULT:未知"));
        list.add(transform("SUBSTR", "截取子串", "写法 SUBSTR:起始:长度"));
        return list;
    }

    private static Map<String, Object> operator(String code, String name, String category, String description,
                                                Map<String, Object>... params) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", name);
        map.put("category", category);
        map.put("description", description);
        map.put("params", List.of(params));
        return map;
    }

    private static Map<String, Object> param(String name, String type, boolean required, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("required", required);
        map.put("description", description);
        return map;
    }

    private static Map<String, Object> transform(String code, String name, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", name);
        map.put("description", description);
        return map;
    }
}
