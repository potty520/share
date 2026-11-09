package com.share.share.dto;

import java.util.Map;

/**
 * 共享 API 在线调试入参（FR-07）。
 *
 * @param params   查询参数键值对（仅接受 API 定义过的参数）
 * @param pageNum  页码，默认 1
 * @param pageSize 每页条数，默认 10
 */
public record ApiTestRequest(Map<String, Object> params, Integer pageNum, Integer pageSize) {

    /** 允许直接传扁平参数（{entName:"x"} 或 {params:{entName:"x"}} 都兼容）。 */
    public Map<String, Object> safeParams() {
        return params == null ? Map.of() : params;
    }
}
