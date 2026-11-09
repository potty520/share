package com.share.catalog.support;

import com.share.catalog.entity.CatalogItem;
import com.share.catalog.entity.CatalogResource;
import com.share.common.util.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目录版本快照构建（FR-03：版本管理）。
 * <p>快照内容为"资源主体 + 数据项 + 挂接信息"的 JSON 文本，由发布/变更/下架时写入
 * {@code cat_catalog_version}，供历史追溯与版本对比。种子数据与运行时共用同一实现。</p>
 */
public final class CatalogSnapshots {

    /** 落库字段上限（cat_catalog_version.snapshot 长度 16000） */
    private static final int MAX_LENGTH = 15900;

    private CatalogSnapshots() {
    }

    public static String build(CatalogResource entity, List<CatalogItem> items) {
        return build(entity, items, entity.getVersionNo());
    }

    /** 构建指定版本号的快照（种子数据补历史版本时使用）。 */
    public static String build(CatalogResource entity, List<CatalogItem> items, Integer versionNo) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("resourceCode", entity.getResourceCode());
        snap.put("resourceName", entity.getResourceName());
        snap.put("summary", entity.getSummary());
        snap.put("classifyId", entity.getClassifyId());
        snap.put("deptId", entity.getDeptId());
        snap.put("deptName", entity.getDeptName());
        snap.put("shareType", entity.getShareType());
        snap.put("level", entity.getLevel());
        snap.put("status", entity.getStatus());
        snap.put("sourceId", entity.getSourceId());
        snap.put("sourceType", entity.getSourceType());
        snap.put("tableName", entity.getTableName());
        snap.put("filePath", entity.getFilePath());
        snap.put("apiUrl", entity.getApiUrl());
        snap.put("updateFrequency", entity.getUpdateFrequency());
        snap.put("providerContact", entity.getProviderContact());
        snap.put("tags", entity.getTags());
        snap.put("versionNo", versionNo);
        snap.put("publishTime", entity.getPublishTime() == null ? null : entity.getPublishTime().toString());
        List<Map<String, Object>> itemList = new ArrayList<>();
        if (items != null) {
            for (CatalogItem item : items) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("fieldName", item.getFieldName());
                map.put("fieldType", item.getFieldType());
                map.put("fieldComment", item.getFieldComment());
                map.put("sensitive", item.getSensitive());
                map.put("maskRuleCode", item.getMaskRuleCode());
                map.put("sortNo", item.getSortNo());
                itemList.add(map);
            }
        }
        snap.put("items", itemList);
        String json = JsonUtil.toJson(snap);
        return json.length() > MAX_LENGTH ? json.substring(0, MAX_LENGTH) : json;
    }
}
