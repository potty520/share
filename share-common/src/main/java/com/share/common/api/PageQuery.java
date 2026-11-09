package com.share.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 统一分页查询参数：pageNum/pageSize/orderBy（orderBy 由各服务做白名单校验）。 */
@Getter
@Setter
public class PageQuery {

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "pageNum 最小为 1")
    private int pageNum = 1;

    /** 每页条数，最大 200 */
    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = 200, message = "pageSize 最大为 200")
    private int pageSize = 10;

    /** 排序字段，如 createTime desc */
    @Pattern(regexp = "^[A-Za-z0-9_ ,.]{0,80}$", message = "orderBy 含非法字符")
    private String orderBy;

    public int offset() {
        return (Math.max(pageNum, 1) - 1) * Math.max(pageSize, 1);
    }
}
