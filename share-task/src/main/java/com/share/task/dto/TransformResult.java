package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 加工转换结果（引擎与试运行共用）。 */
@Getter
@Setter
public class TransformResult {

    /** 转换后的数据行（保持列顺序） */
    private List<Map<String, Object>> rows = new ArrayList<>();

    /** 输出列名（按映射顺序） */
    private List<String> columns = new ArrayList<>();

    /** 输入行数 */
    private int inputRows;

    /** 过滤/清洗掉的行数 */
    private int filteredRows;

    /** 各算子生效统计：[{type, affected}] */
    private List<Map<String, Object>> operatorStats = new ArrayList<>();

    /** 耗时（毫秒） */
    private long costMs;
}
