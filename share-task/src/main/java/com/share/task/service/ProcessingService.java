package com.share.task.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.JsonUtil;
import com.share.task.dto.MappingRule;
import com.share.task.dto.PreviewRequest;
import com.share.task.dto.TransformResult;
import com.share.task.engine.OperatorCatalog;
import com.share.task.engine.TransformEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据加工服务（FR-05）：算子字典查询与加工试运行（纯内存计算，不落库不落盘）。
 */
@Service
@RequiredArgsConstructor
public class ProcessingService {

    private final TransformEngine transformEngine;

    /** 算子字典：算子 + 字段级转换规则 + 分类。 */
    public Map<String, Object> dictionary() {
        List<Map<String, Object>> operators = OperatorCatalog.operators();
        Set<String> categories = new LinkedHashSet<>();
        for (Map<String, Object> operator : operators) {
            categories.add(String.valueOf(operator.get("category")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operators", operators);
        result.put("transforms", OperatorCatalog.transforms());
        result.put("categories", new ArrayList<>(categories));
        result.put("maskTypes", List.of("MASK", "REPLACE", "HASH", "GENERALIZE", "PERTURB", "ENCRYPT", "NONE"));
        return result;
    }

    /** 加工试运行：按映射与算子链转换样例数据并返回统计。 */
    public Map<String, Object> preview(PreviewRequest request) {
        if (request == null || request.getSampleRows() == null || request.getSampleRows().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sampleRows 不能为空");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : request.getSampleRows()) {
            rows.add(new LinkedHashMap<>(row));
        }
        List<MappingRule> mapping = convertMapping(request.getMapping());
        List<Map<String, Object>> operators = request.getOperators() == null ? new ArrayList<>() : request.getOperators();
        TransformResult transform = transformEngine.transform(rows, mapping, operators, request.getFilterCondition());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("inputRows", transform.getInputRows());
        stats.put("outputRows", transform.getRows().size());
        stats.put("filteredRows", transform.getFilteredRows());
        stats.put("costMs", transform.getCostMs());
        stats.put("operatorStats", transform.getOperatorStats());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", transform.getRows());
        result.put("columns", transform.getColumns());
        result.put("mapping", mapping);
        result.put("operators", operators);
        result.put("stats", stats);
        return result;
    }

    /** Map 列表 → MappingRule 列表。 */
    private List<MappingRule> convertMapping(List<Map<String, Object>> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return JsonUtil.mapper().convertValue(mapping, new TypeReference<List<MappingRule>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "映射规则格式不正确：" + e.getMessage());
        }
    }
}
