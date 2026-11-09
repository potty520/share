package com.share.catalog.service;

import com.share.catalog.dto.AuditRequest;
import com.share.catalog.dto.CatalogDTO;
import com.share.catalog.dto.CatalogItemDTO;
import com.share.catalog.dto.CatalogQuery;
import com.share.catalog.dto.CatalogVO;
import com.share.catalog.dto.CatalogVersionVO;
import com.share.catalog.dto.CatalogStatsVO;
import com.share.catalog.dto.PreviewVO;
import com.share.catalog.entity.CatalogItem;
import com.share.catalog.entity.CatalogResource;
import com.share.catalog.entity.CatalogVersion;
import com.share.catalog.entity.DataSource;
import com.share.catalog.repository.CatalogItemRepository;
import com.share.catalog.repository.CatalogResourceRepository;
import com.share.catalog.repository.CatalogVersionRepository;
import com.share.catalog.repository.ClassifyRepository;
import com.share.catalog.repository.DataSourceRepository;
import com.share.catalog.support.CatalogAssembler;
import com.share.catalog.support.CatalogSnapshots;
import com.share.catalog.support.DeptNameResolver;
import com.share.catalog.support.Dicts;
import com.share.common.api.ErrorCode;
import com.share.common.event.NotifyEvent;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import com.share.common.util.IdGen;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据目录服务（FR-03）。
 * <p>覆盖编目维护（含数据项）、分类检索、提交审核 / 发布 / 驳回 / 下架状态机、版本快照、
 * 样例预览与统计。发布后的内容修改会自动递增版本并回到草稿，需重新审核。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CatalogResourceRepository catalogRepository;
    private final CatalogItemRepository itemRepository;
    private final CatalogVersionRepository versionRepository;
    private final ClassifyRepository classifyRepository;
    private final DataSourceRepository sourceRepository;
    private final ClassifyService classifyService;
    private final CatalogAssembler assembler;
    private final PreviewService previewService;
    private final DeptNameResolver deptNameResolver;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 查询 ====================

    /** 目录检索（默认只返回已发布；all=true 查询全部状态，编目管理用）。 */
    public Page<CatalogVO> page(CatalogQuery query) {
        Pageable pageable = PageRequest.of(Math.max(query.getPageNum(), 1) - 1, Math.max(query.getPageSize(), 1),
                resolveSort(query.getOrderBy()));
        Page<CatalogResource> page = catalogRepository.findAll(buildSpec(query), pageable);
        List<CatalogVO> vos = assembler.toListVOs(page.getContent());
        return new PageImpl<>(vos, pageable, page.getTotalElements());
    }

    /** 目录详情（含数据项、历史版本、挂接信息）。 */
    public CatalogVO detail(Long id) {
        return assembler.toDetailVO(requireCatalog(id));
    }

    /** 版本列表。 */
    public List<CatalogVersionVO> versions(Long id) {
        requireCatalog(id);
        List<CatalogVersionVO> list = new ArrayList<>();
        for (CatalogVersion version : versionRepository
                .findByCatalogIdAndDeletedFalseOrderByVersionNoDescIdDesc(id)) {
            list.add(CatalogVersionVO.from(version));
        }
        return list;
    }

    /** 样例数据预览（敏感字段脱敏）。 */
    public PreviewVO preview(Long id, int limit) {
        return previewService.preview(requireCatalog(id), limit);
    }

    // ==================== 编目维护 ====================

    /** 新增目录（草稿态）。 */
    @Transactional
    public CatalogVO create(CatalogDTO dto) {
        if (dto.getResourceName() == null || dto.getResourceName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资源名称不能为空");
        }
        String code = normalizeResourceCode(dto.getResourceCode());
        if (catalogRepository.findByResourceCodeAndDeletedFalse(code).isPresent()) {
            throw new BizException(ErrorCode.CATALOG_CODE_DUPLICATED, "资源编码已存在：" + code);
        }
        CatalogResource entity = new CatalogResource();
        entity.setResourceCode(code);
        entity.setStatus("DRAFT");
        entity.setVersionNo(1);
        entity.setSubmitTime(null);
        applyContent(entity, dto, true);
        CatalogResource saved = catalogRepository.save(entity);
        saveItems(saved.getId(), dto.getItems());
        log.info("目录资源已创建（草稿）: {} {}", saved.getResourceCode(), saved.getResourceName());
        return assembler.toDetailVO(saved);
    }

    /** 修改目录；发布后修改自动递增版本并回到草稿，需重新审核。 */
    @Transactional
    public CatalogVO update(Long id, CatalogDTO dto) {
        CatalogResource entity = requireCatalog(id);
        List<CatalogItem> currentItems = itemRepository
                .findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(id);
        String beforeSignature = entitySignature(entity, currentItems);
        List<CatalogItemDTO> effectiveItems = dto.getItems() != null
                ? dto.getItems() : itemsToDto(currentItems);
        // 先算出“修改后”的签名，判断内容是否真的变化
        CatalogResource probe = new CatalogResource();
        probe.setResourceName(entity.getResourceName());
        probe.setSummary(entity.getSummary());
        probe.setClassifyId(entity.getClassifyId());
        probe.setDeptId(entity.getDeptId());
        probe.setShareType(entity.getShareType());
        probe.setLevel(entity.getLevel());
        probe.setSourceId(entity.getSourceId());
        probe.setSourceType(entity.getSourceType());
        probe.setTableName(entity.getTableName());
        probe.setFilePath(entity.getFilePath());
        probe.setApiUrl(entity.getApiUrl());
        probe.setUpdateFrequency(entity.getUpdateFrequency());
        probe.setProviderContact(entity.getProviderContact());
        probe.setTags(entity.getTags());
        probe.setRemark(entity.getRemark());
        applyContent(probe, dto, false);
        String afterSignature = entitySignature(probe, dtoItemsToEntities(effectiveItems));

        if (!beforeSignature.equals(afterSignature)) {
            String beforeStatus = entity.getStatus();
            if ("PUBLISHED".equals(beforeStatus)) {
                // 发布后修改：版本号 +1，回到草稿等待重新审核（原版本快照已存在，可直接追溯）
                entity.setVersionNo((entity.getVersionNo() == null ? 1 : entity.getVersionNo()) + 1);
                entity.setStatus("DRAFT");
                entity.setAuditComment(null);
                entity.setSubmitTime(null);
            } else if ("PENDING".equals(beforeStatus) || "OFFLINE".equals(beforeStatus)
                    || "REJECTED".equals(beforeStatus)) {
                // 审核中/已下架内容被修改：回到草稿重新走流程
                entity.setStatus("DRAFT");
            }
        }
        applyContent(entity, dto, false);
        CatalogResource saved = catalogRepository.save(entity);
        if (dto.getItems() != null) {
            itemRepository.deleteByCatalogId(id);
            saveItems(id, dto.getItems());
        }
        return assembler.toDetailVO(saved);
    }

    /** 删除目录（逻辑删除；已发布目录需先下架）。 */
    @Transactional
    public void delete(Long id) {
        CatalogResource entity = requireCatalog(id);
        if ("PUBLISHED".equals(entity.getStatus())) {
            throw new BizException(ErrorCode.CATALOG_STATUS_ILLEGAL, "已发布目录请先下架再删除");
        }
        entity.setDeleted(true);
        catalogRepository.save(entity);
        itemRepository.logicDeleteByCatalogId(id);
    }

    /** 提交审核：DRAFT / REJECTED → PENDING。 */
    @Transactional
    public CatalogVO submit(Long id) {
        CatalogResource entity = requireCatalog(id);
        if (!"DRAFT".equals(entity.getStatus()) && !"REJECTED".equals(entity.getStatus())) {
            throw new BizException(ErrorCode.CATALOG_STATUS_ILLEGAL,
                    "仅草稿或已驳回的目录可提交审核，当前状态：" + Dicts.catalogStatusText(entity.getStatus()));
        }
        entity.setStatus("PENDING");
        entity.setSubmitTime(LocalDateTime.now());
        CatalogResource saved = catalogRepository.save(entity);
        publishNotify("目录已提交审核：" + saved.getResourceName(),
                "资源 " + saved.getResourceCode() + " 已提交审核，等待业务管理员审核发布。", "INFO", saved.getId());
        return assembler.toDetailVO(saved);
    }

    /** 审核：PENDING → PUBLISHED（通过，写发布时间 + 版本快照）/ REJECTED（驳回，记录意见）。 */
    @Transactional
    public CatalogVO audit(Long id, AuditRequest request) {
        CatalogResource entity = requireCatalog(id);
        if (!"PENDING".equals(entity.getStatus())) {
            throw new BizException(ErrorCode.CATALOG_STATUS_ILLEGAL,
                    "仅待审核目录可执行审核，当前状态：" + Dicts.catalogStatusText(entity.getStatus()));
        }
        if (request.getApproved() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "审核结论不能为空");
        }
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        entity.setAuditBy(UserContext.username());
        entity.setAuditTime(LocalDateTime.now());
        entity.setAuditComment(request.getComment());
        List<CatalogItem> items = itemRepository.findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(id);
        if (approved) {
            entity.setStatus("PUBLISHED");
            entity.setPublishTime(LocalDateTime.now());
            CatalogResource saved = catalogRepository.save(entity);
            recordVersion(saved, "PUBLISH", "审核通过并发布，版本 V" + saved.getVersionNo(), items);
            publishNotify("目录审核通过：" + saved.getResourceName(),
                    "资源 " + saved.getResourceCode() + " 已发布（V" + saved.getVersionNo() + "），可在数据目录中检索与申请。",
                    "INFO", saved.getId());
            log.info("目录已发布: {} V{}", saved.getResourceCode(), saved.getVersionNo());
            return assembler.toDetailVO(saved);
        }
        entity.setStatus("REJECTED");
        CatalogResource saved = catalogRepository.save(entity);
        recordVersion(saved, "AUDIT_REJECT",
                "审核驳回：" + (request.getComment() == null ? "未填写意见" : request.getComment()), items);
        publishNotify("目录审核驳回：" + saved.getResourceName(),
                "资源 " + saved.getResourceCode() + " 审核未通过，意见：" + (request.getComment() == null ? "无" : request.getComment()),
                "WARN", saved.getId());
        return assembler.toDetailVO(saved);
    }

    /** 下架：PUBLISHED → OFFLINE。 */
    @Transactional
    public CatalogVO offline(Long id) {
        CatalogResource entity = requireCatalog(id);
        if (!"PUBLISHED".equals(entity.getStatus())) {
            throw new BizException(ErrorCode.CATALOG_STATUS_ILLEGAL,
                    "仅已发布目录可下架，当前状态：" + Dicts.catalogStatusText(entity.getStatus()));
        }
        entity.setStatus("OFFLINE");
        CatalogResource saved = catalogRepository.save(entity);
        List<CatalogItem> items = itemRepository.findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(id);
        recordVersion(saved, "OFFLINE", "目录下架，版本 V" + saved.getVersionNo(), items);
        publishNotify("目录已下架：" + saved.getResourceName(),
                "资源 " + saved.getResourceCode() + " 已下架，将不再对外提供共享。", "WARN", saved.getId());
        return assembler.toDetailVO(saved);
    }

    // ==================== 统计 ====================

    /** 目录统计：总数/已发布/待审核/涉敏 + 按部门/共享属性/密级/状态分布。 */
    public CatalogStatsVO stats() {
        CatalogStatsVO vo = new CatalogStatsVO();
        vo.setTotal(catalogRepository.countByDeletedFalse());
        vo.setPublished(catalogRepository.countByStatusAndDeletedFalse("PUBLISHED"));
        vo.setPending(catalogRepository.countByStatusAndDeletedFalse("PENDING"));
        vo.setDraft(catalogRepository.countByStatusAndDeletedFalse("DRAFT"));
        vo.setRejected(catalogRepository.countByStatusAndDeletedFalse("REJECTED"));
        vo.setOffline(catalogRepository.countByStatusAndDeletedFalse("OFFLINE"));
        vo.setSensitive(catalogRepository.countSensitiveCatalog());
        vo.setItemTotal(itemRepository.countByDeletedFalse());
        vo.setSourceTotal(sourceRepository.countByDeletedFalse());
        vo.setClassifyTotal(classifyRepository.countByDeletedFalse());

        Map<Long, String> deptNames = deptNameResolver.all();
        for (Object[] row : catalogRepository.countGroupByDept()) {
            Long deptId = row[0] == null ? null : ((Number) row[0]).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deptId", deptId);
            item.put("deptName", deptId == null ? "未指定" : deptNames.getOrDefault(deptId, "部门" + deptId));
            item.put("count", ((Number) row[1]).longValue());
            vo.getByDept().add(item);
        }
        vo.getByDept().sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));

        for (Object[] row : catalogRepository.countGroupByShareType()) {
            String code = row[0] == null ? null : String.valueOf(row[0]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("label", Dicts.shareTypeText(code));
            item.put("count", ((Number) row[1]).longValue());
            vo.getByShareType().add(item);
        }
        for (Object[] row : catalogRepository.countGroupByLevel()) {
            Integer level = row[0] == null ? null : ((Number) row[0]).intValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("level", level);
            item.put("label", Dicts.secretLevelText(level));
            item.put("count", ((Number) row[1]).longValue());
            vo.getBySecretLevel().add(item);
        }
        for (Object[] row : catalogRepository.countGroupByStatus()) {
            String code = row[0] == null ? null : String.valueOf(row[0]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("label", Dicts.catalogStatusText(code));
            item.put("count", ((Number) row[1]).longValue());
            vo.getByStatus().add(item);
        }
        vo.setStatTime(LocalDateTime.now());
        return vo;
    }

    // ==================== 内部方法 ====================

    private CatalogResource requireCatalog(Long id) {
        return catalogRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.CATALOG_NOT_FOUND));
    }

    /** 把 DTO 内容写入实体（isCreate=true 表示全量写入，否则空值沿用原值）。 */
    private void applyContent(CatalogResource entity, CatalogDTO dto, boolean isCreate) {
        if (dto.getResourceName() != null && !dto.getResourceName().isBlank()) {
            entity.setResourceName(dto.getResourceName().trim());
        }
        if (dto.getSummary() != null || isCreate) {
            entity.setSummary(trimToNull(dto.getSummary()));
        }
        if (dto.getClassifyId() != null) {
            if (!classifyRepository.existsById(dto.getClassifyId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分类节点不存在：" + dto.getClassifyId());
            }
            entity.setClassifyId(dto.getClassifyId());
        }
        if (dto.getDeptId() != null) {
            entity.setDeptId(dto.getDeptId());
        } else if (isCreate && entity.getDeptId() == null && UserContext.deptId() != null) {
            entity.setDeptId(UserContext.deptId());
        }
        String deptName = dto.getDeptName();
        if (deptName != null && !deptName.isBlank()) {
            entity.setDeptName(deptName.trim());
        } else {
            entity.setDeptName(deptNameResolver.resolveOrKeep(entity.getDeptId(), entity.getDeptName()));
        }
        entity.setShareType(normalizeShareType(dto.getShareType(),
                entity.getShareType() == null ? (isCreate ? "CONDITIONAL" : null) : entity.getShareType()));
        Integer level = dto.getSecretLevel() != null ? dto.getSecretLevel() : dto.getLevel();
        if (level != null) {
            entity.setLevel(normalizeSecretLevel(level));
        } else if (isCreate && entity.getLevel() == null) {
            entity.setLevel(2);
        }
        if (dto.getSourceId() != null) {
            DataSource source = sourceRepository.findByIdAndDeletedFalse(dto.getSourceId())
                    .orElseThrow(() -> new BizException(ErrorCode.DATASOURCE_NOT_FOUND));
            entity.setSourceId(source.getId());
            entity.setSourceType(source.getSourceType());
        } else if (isCreate) {
            entity.setSourceId(null);
            entity.setSourceType(trimToNull(dto.getSourceType()));
        } else if (dto.getSourceType() != null) {
            entity.setSourceType(trimToNull(dto.getSourceType()));
        }
        if (dto.getTableName() != null || isCreate) {
            entity.setTableName(trimToNull(dto.getTableName()));
        }
        if (dto.getFilePath() != null || isCreate) {
            entity.setFilePath(trimToNull(dto.getFilePath()));
        }
        if (dto.getApiUrl() != null || isCreate) {
            entity.setApiUrl(trimToNull(dto.getApiUrl()));
        }
        if (dto.getUpdateFrequency() != null || isCreate) {
            entity.setUpdateFrequency(trimToNull(dto.getUpdateFrequency()));
        }
        if (dto.getProviderContact() != null || isCreate) {
            entity.setProviderContact(trimToNull(dto.getProviderContact()));
        }
        if (dto.getTags() != null || isCreate) {
            entity.setTags(CatalogAssembler.normalizeTags(dto.getTags()));
        }
        if (dto.getRemark() != null || isCreate) {
            entity.setRemark(trimToNull(dto.getRemark()));
        }
    }

    /** 覆盖式保存数据项。 */
    private void saveItems(Long catalogId, List<CatalogItemDTO> items) {
        if (items == null) {
            return;
        }
        int sort = 10;
        for (CatalogItemDTO dto : items) {
            if (dto == null || dto.getFieldName() == null || dto.getFieldName().isBlank()) {
                continue;
            }
            CatalogItem item = new CatalogItem();
            item.setCatalogId(catalogId);
            item.setFieldName(dto.getFieldName().trim());
            item.setFieldType(trimToNull(dto.getFieldType()));
            item.setFieldComment(trimToNull(dto.getFieldComment()));
            item.setSampleValue(trimToNull(dto.getSampleValue()));
            item.setSensitive(Boolean.TRUE.equals(dto.getSensitive()));
            item.setMaskRuleCode(trimToNull(dto.getMaskRuleCode()));
            item.setSortNo(dto.getSortNo() == null ? sort : dto.getSortNo());
            sort += 10;
            itemRepository.save(item);
        }
    }

    private List<CatalogItemDTO> itemsToDto(List<CatalogItem> items) {
        List<CatalogItemDTO> list = new ArrayList<>();
        for (CatalogItem item : items) {
            CatalogItemDTO dto = new CatalogItemDTO();
            dto.setFieldName(item.getFieldName());
            dto.setFieldType(item.getFieldType());
            dto.setFieldComment(item.getFieldComment());
            dto.setSampleValue(item.getSampleValue());
            dto.setSensitive(item.getSensitive());
            dto.setMaskRuleCode(item.getMaskRuleCode());
            dto.setSortNo(item.getSortNo());
            list.add(dto);
        }
        return list;
    }

    /** DTO 数据项 → 临时实体（仅用于内容签名比对，不落库）。 */
    private List<CatalogItem> dtoItemsToEntities(List<CatalogItemDTO> dtos) {
        List<CatalogItem> list = new ArrayList<>();
        if (dtos == null) {
            return list;
        }
        int sort = 10;
        for (CatalogItemDTO dto : dtos) {
            if (dto == null || dto.getFieldName() == null || dto.getFieldName().isBlank()) {
                continue;
            }
            CatalogItem item = new CatalogItem();
            item.setFieldName(dto.getFieldName().trim());
            item.setFieldType(trimToNull(dto.getFieldType()));
            item.setFieldComment(trimToNull(dto.getFieldComment()));
            item.setSampleValue(trimToNull(dto.getSampleValue()));
            item.setSensitive(Boolean.TRUE.equals(dto.getSensitive()));
            item.setMaskRuleCode(trimToNull(dto.getMaskRuleCode()));
            item.setSortNo(dto.getSortNo() == null ? sort : dto.getSortNo());
            sort += 10;
            list.add(item);
        }
        return list;
    }

    private String entitySignature(CatalogResource entity, List<CatalogItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append(nvl(entity.getResourceName())).append('|')
                .append(nvl(entity.getSummary())).append('|')
                .append(nvl(entity.getClassifyId())).append('|')
                .append(nvl(entity.getDeptId())).append('|')
                .append(nvl(entity.getShareType())).append('|')
                .append(nvl(entity.getLevel())).append('|')
                .append(nvl(entity.getSourceId())).append('|')
                .append(nvl(entity.getSourceType())).append('|')
                .append(nvl(entity.getTableName())).append('|')
                .append(nvl(entity.getFilePath())).append('|')
                .append(nvl(entity.getApiUrl())).append('|')
                .append(nvl(entity.getUpdateFrequency())).append('|')
                .append(nvl(entity.getProviderContact())).append('|')
                .append(nvl(entity.getTags())).append('|')
                .append(nvl(entity.getRemark())).append("||");
        for (CatalogItem item : items) {
            sb.append(nvl(item.getFieldName())).append(':')
                    .append(nvl(item.getFieldType())).append(':')
                    .append(nvl(item.getFieldComment())).append(':')
                    .append(nvl(item.getSampleValue())).append(':')
                    .append(nvl(item.getSensitive())).append(':')
                    .append(nvl(item.getMaskRuleCode())).append(':')
                    .append(nvl(item.getSortNo())).append(';');
        }
        return sb.toString();
    }

    /** 记录版本快照。 */
    private void recordVersion(CatalogResource entity, String changeType, String changeLog, List<CatalogItem> items) {
        CatalogVersion version = new CatalogVersion();
        version.setCatalogId(entity.getId());
        version.setVersionNo(entity.getVersionNo());
        version.setChangeType(changeType);
        version.setChangeLog(truncate(changeLog, 1000));
        version.setSnapshot(CatalogSnapshots.build(entity, items));
        if ("PUBLISH".equals(changeType)) {
            version.setPublishTime(entity.getPublishTime());
        }
        version.setOperator(UserContext.username());
        versionRepository.save(version);
    }

    private Specification<CatalogResource> buildSpec(CatalogQuery query) {
        List<Long> classifyIds = query.getClassifyId() == null ? List.of()
                : classifyService.selfAndDescendantIds(query.getClassifyId());
        List<Long> sensitiveIds = Boolean.TRUE.equals(query.getSensitive())
                ? catalogRepository.findSensitiveCatalogIds() : List.of();
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (hasText(query.getStatus())) {
                predicates.add(cb.equal(root.get("status"), query.getStatus().trim().toUpperCase(Locale.ROOT)));
            } else if (!Boolean.TRUE.equals(query.getAll())) {
                // 默认只返回已发布目录（数据目录检索场景）
                predicates.add(cb.equal(root.get("status"), "PUBLISHED"));
            }
            if (hasText(query.getKeyword())) {
                String like = "%" + query.getKeyword().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("resourceCode")), like),
                        cb.like(cb.lower(root.get("resourceName")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("summary"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("tags"), "")), like)));
            }
            if (!classifyIds.isEmpty()) {
                predicates.add(root.get("classifyId").in(classifyIds));
            }
            if (query.getDeptId() != null) {
                predicates.add(cb.equal(root.get("deptId"), query.getDeptId()));
            }
            if (hasText(query.getShareType())) {
                predicates.add(cb.equal(root.get("shareType"), query.getShareType().trim().toUpperCase(Locale.ROOT)));
            }
            if (hasText(query.getTag())) {
                predicates.add(cb.like(cb.lower(cb.coalesce(root.<String>get("tags"), "")),
                        "%" + query.getTag().trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (query.getSecretLevel() != null) {
                predicates.add(cb.equal(root.get("level"), query.getSecretLevel()));
            }
            if (Boolean.TRUE.equals(query.getSensitive())) {
                if (sensitiveIds.isEmpty()) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("level"), 3));
                } else {
                    predicates.add(cb.or(cb.greaterThanOrEqualTo(root.get("level"), 3),
                            root.get("id").in(sensitiveIds)));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 排序解析（白名单，默认按更新时间倒序）。 */
    private Sort resolveSort(String orderBy) {
        Sort defaultSort = Sort.by(Sort.Direction.DESC, "updateTime").and(Sort.by(Sort.Direction.DESC, "id"));
        if (orderBy == null || orderBy.isBlank()) {
            return defaultSort;
        }
        String[] parts = orderBy.trim().split("\\s+");
        String field = parts[0];
        List<String> allowed = List.of("createTime", "updateTime", "publishTime", "resourceName", "resourceCode",
                "versionNo", "level");
        if (!allowed.contains(field)) {
            return defaultSort;
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private String normalizeResourceCode(String code) {
        if (code == null || code.isBlank()) {
            return "CAT-" + IdGen.shortId().toUpperCase(Locale.ROOT);
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeShareType(String shareType, String defaultValue) {
        if (shareType == null || shareType.isBlank()) {
            return defaultValue;
        }
        String normalized = shareType.trim().toUpperCase(Locale.ROOT);
        if (!Dicts.SHARE_TYPE.containsKey(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "共享属性只能为 UNCONDITIONAL / CONDITIONAL / NO_SHARE");
        }
        return normalized;
    }

    private Integer normalizeSecretLevel(Integer level) {
        if (level < 1 || level > 4) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密级只能为 1~4");
        }
        return level;
    }

    /** 发布站内通知事件（由 share-ops 消费；未装配时事件静默丢弃）。 */
    private void publishNotify(String title, String content, String level, Long catalogId) {
        try {
            eventPublisher.publishEvent(NotifyEvent.builder()
                    .title(title)
                    .content(content)
                    .bizType("CATALOG")
                    .bizId(String.valueOf(catalogId))
                    .channel("INNER")
                    .level(level)
                    .build());
        } catch (Exception e) {
            log.warn("目录通知事件发布失败: {}", e.getMessage());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nvl(Object value) {
        return value == null ? "" : value.toString();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
