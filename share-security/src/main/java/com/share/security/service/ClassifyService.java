package com.share.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import com.share.common.util.JsonUtil;
import com.share.security.dto.SecurityRequests;
import com.share.security.dto.SecurityVOs;
import com.share.security.entity.SecClassifyTag;
import com.share.security.entity.SecClassifyTemplate;
import com.share.security.repository.SecClassifyTagRepository;
import com.share.security.repository.SecClassifyTemplateRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分类分级服务（FR-10）：模板 CRUD + 目录/字段分级标签维护（单个与批量）。
 */
@Slf4j
@Service("securityClassifyService")
@RequiredArgsConstructor
public class ClassifyService {

    private static final Set<String> TARGET_TYPES = Set.of("CATALOG", "FIELD");

    private final SecClassifyTemplateRepository templateRepository;
    private final SecClassifyTagRepository tagRepository;

    // ==================== 分级模板 ====================

    /** 模板分页列表。 */
    public SecurityVOs.PageResult<SecClassifyTemplate> listTemplates(String keyword, Integer status,
                                                                     int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Specification<SecClassifyTemplate> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("deleted"), false));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("templateCode")), like),
                        cb.like(cb.lower(root.<String>get("templateName")), like)
                ));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<SecClassifyTemplate> result = templateRepository.findAll(spec,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return new SecurityVOs.PageResult<>(result.getContent(), result.getTotalElements());
    }

    public SecClassifyTemplate getTemplate(Long id) {
        return requireTemplate(id);
    }

    /** 新增分类分级模板（1~4 级定义）。 */
    @Transactional
    public SecClassifyTemplate createTemplate(SecurityRequests.TemplateSaveRequest request) {
        if (request.templateCode() == null || request.templateCode().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板编码不能为空");
        }
        String code = request.templateCode().trim().toUpperCase(Locale.ROOT);
        if (templateRepository.existsByTemplateCode(code)) {
            throw new BizException(ErrorCode.CONFLICT, "模板编码已存在: " + code);
        }
        if (request.templateName() == null || request.templateName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板名称不能为空");
        }
        SecClassifyTemplate template = new SecClassifyTemplate();
        template.setTemplateCode(code);
        template.setTemplateName(request.templateName().trim());
        template.setDescription(request.description());
        template.setLevelDefs(normalizeLevelDefs(request.levelDefs()));
        template.setStatus(request.status() == null ? 1 : request.status());
        template.setBuiltin(false);
        SecClassifyTemplate saved = templateRepository.save(template);
        log.info("新增分级模板: code={}, levels={}", code, levelCount(saved.getLevelDefs()));
        return saved;
    }

    /** 修改模板。 */
    @Transactional
    public SecClassifyTemplate updateTemplate(Long id, SecurityRequests.TemplateSaveRequest request) {
        SecClassifyTemplate template = requireTemplate(id);
        if (request.templateCode() != null && !request.templateCode().isBlank()
                && !request.templateCode().trim().equalsIgnoreCase(template.getTemplateCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板编码创建后不可修改");
        }
        if (request.templateName() != null && !request.templateName().isBlank()) {
            template.setTemplateName(request.templateName().trim());
        }
        if (request.description() != null) {
            template.setDescription(request.description());
        }
        if (request.levelDefs() != null) {
            template.setLevelDefs(normalizeLevelDefs(request.levelDefs()));
        }
        if (request.status() != null) {
            template.setStatus(request.status());
        }
        templateRepository.save(template);
        log.info("修改分级模板: id={}", id);
        return template;
    }

    /** 逻辑删除模板（内置模板不可删）。 */
    @Transactional
    public void deleteTemplate(Long id) {
        SecClassifyTemplate template = requireTemplate(id);
        if (Boolean.TRUE.equals(template.getBuiltin())) {
            throw new BizException(ErrorCode.CONFLICT, "内置模板不允许删除");
        }
        template.setDeleted(true);
        template.setStatus(0);
        templateRepository.save(template);
        log.info("删除分级模板: id={}, code={}", id, template.getTemplateCode());
    }

    /** 校验并规范化 1~4 级定义 JSON（必须包含 1 级，最多 4 级）。 */
    private String normalizeLevelDefs(Object levelDefs) {
        if (levelDefs == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "levelDefs 不能为空");
        }
        String json;
        if (levelDefs instanceof String text) {
            json = text.trim();
        } else {
            json = JsonUtil.toJson(levelDefs);
        }
        List<Map<String, Object>> defs;
        try {
            defs = JsonUtil.fromJson(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "levelDefs 必须是 JSON 数组");
        }
        if (defs == null || defs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "levelDefs 不能为空数组");
        }
        if (defs.size() > 4) {
            throw new BizException(ErrorCode.BAD_REQUEST, "最多定义 4 个分级");
        }
        Set<Integer> levels = new HashSet<>();
        for (Map<String, Object> def : defs) {
            Object levelValue = def.get("level");
            if (!(levelValue instanceof Number number)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "每个分级必须包含数字 level");
            }
            int level = number.intValue();
            if (level < 1 || level > 4) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分级 level 取值范围为 1~4");
            }
            levels.add(level);
        }
        if (!levels.contains(1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分级定义必须包含 1 级");
        }
        Integer min = levels.stream().min(Integer::compareTo).orElse(1);
        Integer max = levels.stream().max(Integer::compareTo).orElse(4);
        if (max - min + 1 != levels.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分级必须连续定义（如 1~4）");
        }
        return json;
    }

    private int levelCount(String levelDefs) {
        try {
            List<Map<String, Object>> defs = JsonUtil.fromJson(levelDefs,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return defs == null ? 0 : defs.size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 分级标签 ====================

    /** 分级标签分页查询。 */
    public SecurityVOs.PageResult<SecClassifyTag> listTags(String targetType, Long targetId, Integer level,
                                                           String keyword, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        String normalizedType = (targetType == null || targetType.isBlank()) ? null : normalizeTargetType(targetType);
        Specification<SecClassifyTag> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("deleted"), false));
            if (normalizedType != null) {
                predicates.add(cb.equal(root.get("targetType"), normalizedType));
            }
            if (targetId != null) {
                predicates.add(cb.equal(root.get("targetId"), targetId));
            }
            if (level != null) {
                predicates.add(cb.equal(root.get("level"), level));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.<String>get("targetName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("fieldName"), "")), like)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<SecClassifyTag> result = tagRepository.findAll(spec,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return new SecurityVOs.PageResult<>(result.getContent(), result.getTotalElements());
    }

    /** 单个目标打标（同目标同字段存在时覆盖更新）。 */
    @Transactional
    public SecClassifyTag tag(SecurityRequests.TagSaveRequest request) {
        return upsertTag(request.targetType(), request.targetId(), request.targetName(), request.templateId(),
                request.fieldName(), request.level(), request.levelName(), request.remark());
    }

    /** 批量打标：目标类型/模板/密级可继承批量参数。 */
    @Transactional
    public List<SecClassifyTag> batchTag(SecurityRequests.TagBatchRequest request) {
        if (request.tags() == null || request.tags().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "tags 不能为空");
        }
        String targetType = normalizeTargetType(request.targetType());
        List<SecClassifyTag> saved = new ArrayList<>();
        for (SecurityRequests.TagItem item : request.tags()) {
            Integer level = item.level() != null ? item.level() : request.level();
            String levelName = item.levelName() != null ? item.levelName() : request.levelName();
            saved.add(upsertTag(targetType, item.targetId(), item.targetName(), request.templateId(),
                    item.fieldName(), level, levelName, item.remark()));
        }
        log.info("批量打标完成: targetType={}, count={}", targetType, saved.size());
        return saved;
    }

    /** 删除分级标签（逻辑删除）。 */
    @Transactional
    public void deleteTag(Long id) {
        SecClassifyTag tagItem = tagRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.SEC_CLASSIFY_NOT_FOUND));
        tagItem.setDeleted(true);
        tagRepository.save(tagItem);
        log.info("删除分级标签: id={}, target={}#{}", id, tagItem.getTargetId(), tagItem.getFieldName());
    }

    /** 目标类型规范化：CATALOG（目录）/ FIELD（字段）。 */
    public static String normalizeTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "targetType 不能为空");
        }
        String type = targetType.trim().toUpperCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "targetType 仅支持 CATALOG / FIELD");
        }
        return type;
    }

    private SecClassifyTag upsertTag(String targetType, Long targetId, String targetName, Long templateId,
                                     String fieldName, Integer level, String levelName, String remark) {
        String type = normalizeTargetType(targetType);
        if (targetId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "targetId 不能为空");
        }
        if ("FIELD".equals(type) && (fieldName == null || fieldName.isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "字段级打标必须提供 fieldName");
        }
        if (level == null || level < 1 || level > 4) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密级取值范围为 1~4");
        }
        if (templateId != null) {
            requireTemplate(templateId);
        }
        String normalizedField = "CATALOG".equals(type) ? null : fieldName.trim();
        List<SecClassifyTag> existing = tagRepository.findByTargetTypeAndTargetIdAndDeletedFalse(type, targetId);
        SecClassifyTag entity = existing.stream()
                .filter(t -> Objects.equals(t.getFieldName(), normalizedField))
                .findFirst()
                .orElseGet(SecClassifyTag::new);
        entity.setTargetType(type);
        entity.setTargetId(targetId);
        entity.setTargetName(targetName);
        entity.setTemplateId(templateId);
        entity.setFieldName(normalizedField);
        entity.setLevel(level);
        entity.setLevelName(levelName == null || levelName.isBlank()
                ? SecurityMaskService.LEVEL_NAMES[level - 1] : levelName.trim());
        entity.setTagSource("MANUAL");
        entity.setTaggedBy(UserContext.username());
        entity.setTaggedTime(LocalDateTime.now());
        entity.setRemark(remark);
        return tagRepository.save(entity);
    }

    private SecClassifyTemplate requireTemplate(Long id) {
        return templateRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.SEC_CLASSIFY_NOT_FOUND));
    }
}
