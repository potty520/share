package com.share.ops.support;

import org.springframework.data.domain.Page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分页结果装配工具：实体 → 前端友好的 Map（避免直接暴露 JPA 代理字段）。 */
public final class PageSupport {

    private PageSupport() {
    }

    public static int pageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public static int pageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 200);
    }

    /** 把实体映射函数应用到分页内容，并附加 pageNum/pageSize/total。 */
    public static <S, T> Map<String, Object> of(Page<S> page, java.util.function.Function<S, T> mapper) {
        List<T> list = new ArrayList<>(page.getContent().size());
        page.getContent().forEach(e -> list.add(mapper.apply(e)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", list);
        body.put("total", page.getTotalElements());
        body.put("pageNum", page.getNumber() + 1);
        body.put("pageSize", page.getSize());
        body.put("pages", page.getTotalPages());
        return body;
    }

    public static <S, T> List<T> mapList(List<S> source, java.util.function.Function<S, T> mapper) {
        List<T> list = new ArrayList<>(source.size());
        source.forEach(e -> list.add(mapper.apply(e)));
        return list;
    }
}
