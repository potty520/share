package com.share.share.support;

import java.util.List;

/**
 * 分页结果载体（配合 {@code Result.ok(list, total, pageNum, pageSize)} 使用）。
 *
 * @param list     当前页数据
 * @param total    总条数
 * @param pageNum  页码
 * @param pageSize 每页条数
 */
public record PageResult<T>(List<T> list, long total, int pageNum, int pageSize) {

    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        return new PageResult<>(list, total, pageNum, pageSize);
    }
}
