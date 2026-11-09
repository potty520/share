package com.share.catalog.support;

import com.share.catalog.dto.CatalogItemVO;
import com.share.catalog.dto.CatalogVO;
import com.share.catalog.dto.CatalogVersionVO;
import com.share.catalog.entity.CatalogItem;
import com.share.catalog.entity.CatalogResource;
import com.share.catalog.entity.CatalogVersion;
import com.share.catalog.entity.Classify;
import com.share.catalog.entity.DataSource;
import com.share.catalog.repository.CatalogItemRepository;
import com.share.catalog.repository.CatalogVersionRepository;
import com.share.catalog.repository.ClassifyRepository;
import com.share.catalog.repository.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 目录实体 → 响应对象装配器。
 * <p>统一处理密级双字段（secretLevel/level）、状态文案、涉敏标记、数据项/版本/挂接信息，避免各处重复映射。</p>
 */
@Component
@RequiredArgsConstructor
public class CatalogAssembler {

    private final CatalogItemRepository itemRepository;
    private final CatalogVersionRepository versionRepository;
    private final DataSourceRepository sourceRepository;
    private final ClassifyRepository classifyRepository;

    /** 列表页批量装配（带批量统计数据项数量，避免逐条查库）。 */
    public List<CatalogVO> toListVOs(List<CatalogResource> resources) {
        List<CatalogVO> result = new ArrayList<>();
        if (resources.isEmpty()) {
            return result;
        }
        List<Long> ids = resources.stream().map(CatalogResource::getId).toList();
        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] row : itemRepository.countGroupByCatalogIds(ids)) {
            Long catalogId = ((Number) row[0]).longValue();
            long total = ((Number) row[1]).longValue();
            long sensitive = row[2] == null ? 0L : ((Number) row[2]).longValue();
            counts.put(catalogId, new long[]{total, sensitive});
        }
        Map<Long, String> classifyNames = classifyNames();
        for (CatalogResource resource : resources) {
            CatalogVO vo = base(resource, classifyNames.get(resource.getClassifyId()));
            long[] count = counts.getOrDefault(resource.getId(), new long[]{0L, 0L});
            vo.setItemCount(count[0]);
            vo.setSensitiveItemCount(count[1]);
            vo.setSensitive(isSensitive(resource, count[1]));
            result.add(vo);
        }
        return result;
    }

    /** 详情装配：含数据项、历史版本、挂接信息。 */
    public CatalogVO toDetailVO(CatalogResource resource) {
        String classifyName = resource.getClassifyId() == null ? null
                : classifyRepository.findById(resource.getClassifyId()).map(Classify::getName).orElse(null);
        CatalogVO vo = base(resource, classifyName);
        List<CatalogItem> items = itemRepository
                .findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(resource.getId());
        List<CatalogItemVO> itemVOs = new ArrayList<>();
        long sensitiveItems = 0;
        for (CatalogItem item : items) {
            itemVOs.add(CatalogItemVO.from(item));
            if (Boolean.TRUE.equals(item.getSensitive())) {
                sensitiveItems++;
            }
        }
        vo.setItems(itemVOs);
        vo.setItemCount(itemVOs.size());
        vo.setSensitiveItemCount(sensitiveItems);
        vo.setSensitive(isSensitive(resource, sensitiveItems));

        List<CatalogVersionVO> versions = new ArrayList<>();
        for (CatalogVersion version : versionRepository
                .findByCatalogIdAndDeletedFalseOrderByVersionNoDescIdDesc(resource.getId())) {
            versions.add(CatalogVersionVO.from(version));
        }
        vo.setVersions(versions);
        vo.setAttach(buildAttach(resource));
        return vo;
    }

    /** 仅数据项列表。 */
    public List<CatalogItemVO> itemVOs(Long catalogId) {
        List<CatalogItemVO> list = new ArrayList<>();
        for (CatalogItem item : itemRepository.findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(catalogId)) {
            list.add(CatalogItemVO.from(item));
        }
        return list;
    }

    // ==================== 内部方法 ====================

    private CatalogVO base(CatalogResource entity, String classifyName) {
        CatalogVO vo = new CatalogVO();
        vo.setId(entity.getId());
        vo.setResourceCode(entity.getResourceCode());
        vo.setResourceName(entity.getResourceName());
        vo.setSummary(entity.getSummary());
        vo.setClassifyId(entity.getClassifyId());
        vo.setClassifyName(classifyName);
        vo.setDeptId(entity.getDeptId());
        vo.setDeptName(entity.getDeptName());
        vo.setShareType(entity.getShareType());
        vo.setShareTypeText(Dicts.shareTypeText(entity.getShareType()));
        vo.setSecretLevel(entity.getLevel());
        vo.setLevel(entity.getLevel());
        vo.setSecretLevelText(Dicts.secretLevelText(entity.getLevel()));
        vo.setStatus(entity.getStatus());
        vo.setStatusText(Dicts.catalogStatusText(entity.getStatus()));
        vo.setSourceId(entity.getSourceId());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceTypeName(entity.getSourceType() == null ? null : SourceTypes.nameOf(entity.getSourceType()));
        vo.setTableName(entity.getTableName());
        vo.setFilePath(entity.getFilePath());
        vo.setApiUrl(entity.getApiUrl());
        vo.setUpdateFrequency(entity.getUpdateFrequency());
        vo.setProviderContact(entity.getProviderContact());
        vo.setTags(entity.getTags());
        vo.setTagList(splitTags(entity.getTags()));
        vo.setVersionNo(entity.getVersionNo());
        vo.setPublishTime(entity.getPublishTime());
        vo.setSubmitTime(entity.getSubmitTime());
        vo.setAuditComment(entity.getAuditComment());
        vo.setAuditBy(entity.getAuditBy());
        vo.setAuditTime(entity.getAuditTime());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setCreateBy(entity.getCreateBy());
        vo.setUpdateBy(entity.getUpdateBy());
        return vo;
    }

    private CatalogVO.Attach buildAttach(CatalogResource resource) {
        CatalogVO.Attach attach = new CatalogVO.Attach();
        attach.setSourceId(resource.getSourceId());
        attach.setSourceType(resource.getSourceType());
        attach.setSourceTypeName(resource.getSourceType() == null ? null : SourceTypes.nameOf(resource.getSourceType()));
        attach.setTableName(resource.getTableName());
        attach.setFilePath(resource.getFilePath());
        attach.setApiUrl(resource.getApiUrl());
        if (notBlank(resource.getTableName())) {
            attach.setMode("TABLE");
        } else if (notBlank(resource.getFilePath())) {
            attach.setMode("FILE");
        } else if (notBlank(resource.getApiUrl())) {
            attach.setMode("API");
        } else {
            attach.setMode("NONE");
        }
        attach.setPreviewable(!"NONE".equals(attach.getMode()));
        if (resource.getSourceId() != null) {
            DataSource source = sourceRepository.findByIdAndDeletedFalse(resource.getSourceId()).orElse(null);
            if (source != null) {
                attach.setSourceName(source.getSourceName());
                attach.setSourceEnabled("ENABLED".equalsIgnoreCase(source.getStatus()));
            }
        }
        return attach;
    }

    /** 部门 ID → 部门名称（仅用于统计展示）。 */
    public Map<Long, String> deptNames(List<CatalogResource> resources) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (CatalogResource resource : resources) {
            if (resource.getDeptId() != null) {
                names.put(resource.getDeptId(), resource.getDeptName());
            }
        }
        return names;
    }

    private Map<Long, String> classifyNames() {
        Map<Long, String> names = new HashMap<>();
        for (Classify entity : classifyRepository.findByDeletedFalseOrderBySortNoAscIdAsc()) {
            names.put(entity.getId(), entity.getName());
        }
        return names;
    }

    private boolean isSensitive(CatalogResource resource, long sensitiveItems) {
        return sensitiveItems > 0 || (resource.getLevel() != null && resource.getLevel() >= 3);
    }

    private List<String> splitTags(String tags) {
        List<String> list = new ArrayList<>();
        if (tags == null || tags.isBlank()) {
            return list;
        }
        for (String tag : tags.split("[,，;；]")) {
            if (!tag.isBlank()) {
                list.add(tag.trim());
            }
        }
        return list;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 标签规整：支持字符串 / 数组入参，统一存逗号分隔文本。 */
    public static String normalizeTags(Object tags) {
        if (tags == null) {
            return null;
        }
        if (tags instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (tags instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !item.toString().isBlank()) {
                    parts.add(item.toString().trim());
                }
            }
            return parts.isEmpty() ? null : String.join(",", parts);
        }
        return Objects.toString(tags, null);
    }
}
