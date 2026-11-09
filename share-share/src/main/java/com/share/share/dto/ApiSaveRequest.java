package com.share.share.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 共享 API 新增/修改入参（FR-07）。
 *
 * @param apiCode      服务编码，全局唯一
 * @param apiName      服务名称
 * @param catalogId    绑定目录资源 ID
 * @param catalogName  目录资源名称（目录不可读时可只传名称）
 * @param sourceId     绑定数据源 ID
 * @param sourceTable  挂接物理表名
 * @param httpMethod   HTTP 方法，默认 GET
 * @param paramsJson   参数定义 JSON 数组
 * @param returnFields 返回字段（逗号分隔）
 * @param maskFields   脱敏字段（逗号分隔）
 * @param qpsLimit     单凭据 QPS 限额
 * @param owner        责任人
 * @param deptId       归属部门
 * @param remark       备注
 */
public record ApiSaveRequest(
        @NotBlank(message = "apiCode 不能为空") String apiCode,
        @NotBlank(message = "apiName 不能为空") String apiName,
        Long catalogId,
        String catalogName,
        Long sourceId,
        String sourceTable,
        String httpMethod,
        String paramsJson,
        String returnFields,
        String maskFields,
        Integer qpsLimit,
        String owner,
        Long deptId,
        String remark,
        String status) {
}
