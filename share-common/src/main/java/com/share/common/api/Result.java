package com.share.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.share.common.exception.BizException;
import com.share.common.web.TraceIdHolder;
import lombok.Getter;
import lombok.Setter;

/**
 * 统一响应体：{ code, message, traceId, data, page }
 * <p>code=0 表示成功；业务错误码见 {@link ErrorCode}。</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Result<T> {

    private int code;
    private String message;
    private String traceId;
    private T data;
    private PageMeta page;

    public Result() {
        this.traceId = TraceIdHolder.get();
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok(T data, long total, int pageNum, int pageSize) {
        Result<T> r = ok(data);
        r.page = new PageMeta(pageNum, pageSize, total);
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> Result<T> fail(BizException e) {
        return fail(e.getCode(), e.getMessage());
    }

    public boolean isSuccess() {
        return code == 0;
    }

    /** 分页元数据（与文档 8 章接口规范保持一致） */
    @Getter
    @Setter
    public static class PageMeta {
        private int pageNum;
        private int pageSize;
        private long total;
        private long pages;
        private String backend;

        public PageMeta() {
        }

        public PageMeta(int pageNum, int pageSize, long total) {
            this.pageNum = pageNum;
            this.pageSize = pageSize;
            this.total = total;
            this.pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        }
    }
}
