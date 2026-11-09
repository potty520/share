package com.share.task.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 任务新增/修改请求（FR-04）：同时兼容“结构化数组”和“JSON 字符串”两种入参写法，
 * 前端画布可直接传 mapping/operators 数组，接口调用也可传 mappingJson/operatorsJson。
 */
@Getter
@Setter
public class TaskRequest {

    private String taskCode;
    private String taskName;
    private String taskType;
    private Long sourceId;
    private String sourceName;
    private String sourceType;
    private String sourceTable;
    private String sourceFilePath;
    private String targetType;
    private String targetTable;
    private String targetFilePath;
    private Long catalogId;
    private String filterCondition;
    private String scheduleCron;
    private Boolean enabled;
    private String status;
    private Long deptId;
    private String deptName;
    private String remark;

    /** 映射规则 JSON 字符串 */
    private String mappingJson;

    /** 映射规则结构化数组 */
    private List<Map<String, Object>> mapping;

    /** 算子链 JSON 字符串 */
    private String operatorsJson;

    /** 算子链结构化数组 */
    private List<Map<String, Object>> operators;

    /** 变更说明（写入任务版本记录） */
    private String changeNote;
}
