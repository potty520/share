package com.share.task.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.share.common.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** 实体/对象 → Map 转换工具（接口层统一返回小驼峰字段）。 */
public final class BeanMaps {

    private BeanMaps() {
    }

    /** 把实体转成有序 Map，便于追加扩展字段而不污染实体。 */
    public static Map<String, Object> toMap(Object bean) {
        if (bean == null) {
            return null;
        }
        Map<String, Object> map = JsonUtil.mapper().convertValue(bean, new TypeReference<Map<String, Object>>() {
        });
        return new LinkedHashMap<>(map);
    }
}
