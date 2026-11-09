package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** 加工试运行请求（FR-05）：纯内存计算，不写库、不落盘。 */
@Getter
@Setter
public class PreviewRequest {

    /** 样例数据行 */
    private List<Map<String, Object>> sampleRows;

    /** 字段映射规则 */
    private List<Map<String, Object>> mapping;

    /** 算子链 */
    private List<Map<String, Object>> operators;

    /** 过滤条件，如 status = 'NORMAL' */
    private String filterCondition;
}
