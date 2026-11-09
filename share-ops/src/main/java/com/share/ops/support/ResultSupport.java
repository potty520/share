package com.share.ops.support;

import com.share.common.api.Result;

import java.util.List;
import java.util.Map;

/** 统一响应装配：把服务层的 Map 分页体转成 {@link Result#ok(Object, long, int, int)}。 */
public final class ResultSupport {

    private ResultSupport() {
    }

    @SuppressWarnings("unchecked")
    public static <T> Result<List<T>> paged(Map<String, Object> body) {
        List<T> list = body.get("list") == null ? List.of() : (List<T>) body.get("list");
        long total = body.get("total") == null ? list.size() : ((Number) body.get("total")).longValue();
        int pageNum = body.get("pageNum") == null ? 1 : ((Number) body.get("pageNum")).intValue();
        int pageSize = body.get("pageSize") == null ? 10 : ((Number) body.get("pageSize")).intValue();
        Result<List<T>> result = Result.ok(list, total, pageNum, pageSize);
        Object backend = body.get("backend");
        if (backend != null && result.getPage() != null) {
            result.getPage().setBackend(String.valueOf(backend));
        }
        return result;
    }
}
