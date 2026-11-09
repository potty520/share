package com.share.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.share.common.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** 实体 → Map 工具（agent 接口统一返回小驼峰字段）。 */
public final class AgentMaps {

    private AgentMaps() {
    }

    public static Map<String, Object> toMap(Object bean) {
        if (bean == null) {
            return null;
        }
        Map<String, Object> map = JsonUtil.mapper().convertValue(bean, new TypeReference<Map<String, Object>>() {
        });
        return new LinkedHashMap<>(map);
    }
}
