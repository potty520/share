package com.share.catalog.service;

import com.share.catalog.dto.ClassifyDTO;
import com.share.catalog.dto.ClassifyVO;
import com.share.catalog.entity.Classify;
import com.share.catalog.repository.CatalogResourceRepository;
import com.share.catalog.repository.ClassifyRepository;
import com.share.catalog.support.Dicts;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.IdGen;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 目录分类树服务（FR-03）。
 * <p>支持主题 / 部门 / 业务领域多维度分类，返回树结构；分类下存在子节点或已挂接目录时禁止删除。</p>
 */
@Slf4j
@Service("catalogClassifyService")
@RequiredArgsConstructor
public class ClassifyService {

    private final ClassifyRepository classifyRepository;
    private final CatalogResourceRepository catalogRepository;

    // ==================== 查询 ====================

    /**
     * 分类树。
     *
     * @param dimension 维度过滤（为空返回全部维度）
     * @param flat      是否返回平铺列表（前端下拉用）
     */
    public List<ClassifyVO> tree(String dimension, boolean flat) {
        List<Classify> all = hasText(dimension)
                ? classifyRepository.findByDimensionAndDeletedFalseOrderBySortNoAscIdAsc(dimension.trim().toUpperCase(Locale.ROOT))
                : classifyRepository.findByDeletedFalseOrderBySortNoAscIdAsc();
        Map<Long, ClassifyVO> index = new LinkedHashMap<>();
        for (Classify entity : all) {
            ClassifyVO vo = ClassifyVO.from(entity);
            vo.setResourceCount(catalogRepository.countByClassifyIdAndStatusAndDeletedFalse(entity.getId(), "PUBLISHED"));
            index.put(entity.getId(), vo);
        }
        List<ClassifyVO> roots = new ArrayList<>();
        for (Classify entity : all) {
            ClassifyVO vo = index.get(entity.getId());
            ClassifyVO parent = entity.getParentId() == null ? null : index.get(entity.getParentId());
            if (parent == null) {
                roots.add(vo);
            } else {
                parent.getChildren().add(vo);
            }
        }
        if (flat) {
            List<ClassifyVO> flatList = new ArrayList<>(index.values());
            if (hasText(dimension)) {
                flatList.removeIf(vo -> !dimension.trim().equalsIgnoreCase(vo.getDimension()));
            }
            return flatList;
        }
        return roots;
    }

    /** 分类详情（含子节点）。 */
    public ClassifyVO detail(Long id) {
        Classify entity = requireClassify(id);
        ClassifyVO vo = ClassifyVO.from(entity);
        vo.setResourceCount(catalogRepository.countByClassifyIdAndStatusAndDeletedFalse(id, "PUBLISHED"));
        for (Classify child : classifyRepository.findByParentIdAndDeletedFalse(id)) {
            vo.getChildren().add(ClassifyVO.from(child));
        }
        return vo;
    }

    // ==================== 变更 ====================

    /** 新增分类节点。 */
    @Transactional
    public ClassifyVO create(ClassifyDTO dto) {
        String dimension = normalizeDimension(dto.getDimension(), null);
        if (dimension == null && dto.getParentId() != null) {
            // 未显式指定维度时沿用父节点维度
            dimension = requireClassify(dto.getParentId()).getDimension();
        }
        if (dimension == null) {
            // 缺省归入主题维度，便于前端只填名称即可建树
            dimension = "SUBJECT";
        }
        Classify entity = new Classify();
        entity.setName(dto.getName().trim());
        entity.setDimension(dimension);
        entity.setParentId(resolveParentId(dto.getParentId(), dimension, null));
        entity.setSortNo(dto.getSortNo() == null ? nextSortNo(entity.getParentId()) : dto.getSortNo());
        entity.setDescription(dto.getDescription());
        entity.setCode(resolveCode(dto.getCode(), dimension, null));
        Classify saved = classifyRepository.save(entity);
        log.info("分类节点已创建: {} ({})", saved.getName(), saved.getCode());
        return ClassifyVO.from(saved);
    }

    /** 修改分类节点。 */
    @Transactional
    public ClassifyVO update(Long id, ClassifyDTO dto) {
        Classify entity = requireClassify(id);
        String dimension = normalizeDimension(dto.getDimension(), entity.getDimension());
        entity.setDimension(dimension);
        if (dto.getName() != null && !dto.getName().isBlank()) {
            entity.setName(dto.getName().trim());
        }
        entity.setParentId(resolveParentId(dto.getParentId(), dimension, id));
        if (dto.getSortNo() != null) {
            entity.setSortNo(dto.getSortNo());
        }
        if (dto.getCode() != null && !dto.getCode().isBlank()
                && (entity.getCode() == null || !dto.getCode().trim().equalsIgnoreCase(entity.getCode()))) {
            entity.setCode(resolveCode(dto.getCode(), dimension, id));
        }
        entity.setDescription(dto.getDescription() == null ? entity.getDescription() : dto.getDescription());
        return ClassifyVO.from(classifyRepository.save(entity));
    }

    /** 删除分类节点（存在子分类或已挂接目录时拒绝）。 */
    @Transactional
    public void delete(Long id) {
        Classify entity = requireClassify(id);
        long children = classifyRepository.findByParentIdAndDeletedFalse(id).size();
        if (children > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存在子分类，不能删除");
        }
        long catalogs = catalogRepository.countByClassifyIdAndDeletedFalse(id);
        if (catalogs > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该分类下已挂接 " + catalogs + " 个目录资源，不能删除");
        }
        entity.setDeleted(true);
        classifyRepository.save(entity);
    }

    /** 分类及其所有后代 ID（目录检索按分类树过滤时使用）。 */
    public List<Long> selfAndDescendantIds(Long classifyId) {
        List<Long> result = new ArrayList<>();
        if (classifyId == null) {
            return result;
        }
        List<Classify> all = classifyRepository.findByDeletedFalseOrderBySortNoAscIdAsc();
        Map<Long, List<Long>> childrenMap = new LinkedHashMap<>();
        for (Classify entity : all) {
            childrenMap.computeIfAbsent(entity.getParentId(), k -> new ArrayList<>()).add(entity.getId());
        }
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(classifyId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (result.contains(current)) {
                continue;
            }
            result.add(current);
            queue.addAll(childrenMap.getOrDefault(current, List.of()));
        }
        return result;
    }

    // ==================== 内部方法 ====================

    private Classify requireClassify(Long id) {
        return classifyRepository.findById(id)
                .filter(entity -> !Boolean.TRUE.equals(entity.getDeleted()))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "分类节点不存在"));
    }

    /** 校验父节点：不能挂到自身或自己的后代下，且维度必须一致。 */
    private Long resolveParentId(Long parentId, String dimension, Long selfId) {
        if (parentId == null) {
            return null;
        }
        if (selfId != null && parentId.equals(selfId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "父节点不能是自己");
        }
        Classify parent = requireClassify(parentId);
        if (!dimension.equalsIgnoreCase(parent.getDimension())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "父子分类必须是同一维度");
        }
        if (selfId != null && selfAndDescendantIds(selfId).contains(parentId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "父节点不能是自己的子分类");
        }
        return parentId;
    }

    private Integer nextSortNo(Long parentId) {
        List<Classify> siblings = parentId == null
                ? classifyRepository.findByDeletedFalseOrderBySortNoAscIdAsc()
                : classifyRepository.findByParentIdAndDeletedFalse(parentId);
        int max = 0;
        for (Classify sibling : siblings) {
            int sortNo = sibling.getSortNo() == null ? 0 : sibling.getSortNo();
            if (parentId == null && sibling.getParentId() != null) {
                continue;
            }
            max = Math.max(max, sortNo);
        }
        return max + 10;
    }

    private String resolveCode(String code, String dimension, Long selfId) {
        List<Classify> all = classifyRepository.findByDeletedFalseOrderBySortNoAscIdAsc();
        String candidate = code == null || code.isBlank()
                ? dimension.substring(0, 3) + "-" + IdGen.shortId().substring(0, 6).toUpperCase(Locale.ROOT)
                : code.trim().toUpperCase(Locale.ROOT);
        boolean duplicated = all.stream()
                .anyMatch(entity -> !entity.getId().equals(selfId)
                        && entity.getCode() != null
                        && entity.getCode().equalsIgnoreCase(candidate));
        if (duplicated) {
            throw new BizException(ErrorCode.CONFLICT, "分类编码已存在：" + candidate);
        }
        return candidate;
    }

    private String normalizeDimension(String dimension, String defaultValue) {
        if (dimension == null || dimension.isBlank()) {
            return defaultValue;
        }
        String normalized = dimension.trim().toUpperCase(Locale.ROOT);
        if (!Dicts.DIMENSION.containsKey(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分类维度只能为 SUBJECT / DEPT / DOMAIN");
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
