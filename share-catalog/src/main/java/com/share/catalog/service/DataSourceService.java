package com.share.catalog.service;

import com.share.catalog.dto.DataSourceDTO;
import com.share.catalog.dto.DataSourceLogVO;
import com.share.catalog.dto.DataSourceQuery;
import com.share.catalog.dto.DataSourceVO;
import com.share.catalog.dto.SourceTestResult;
import com.share.catalog.dto.StatusRequest;
import com.share.catalog.entity.ConnectorType;
import com.share.catalog.entity.DataSource;
import com.share.catalog.entity.DataSourceLog;
import com.share.catalog.repository.ConnectorTypeRepository;
import com.share.catalog.repository.DataSourceLogRepository;
import com.share.catalog.repository.DataSourceRepository;
import com.share.catalog.support.DeptNameResolver;
import com.share.catalog.support.SourceTypes;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.CryptoUtil;
import com.share.common.util.JsonUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 数据源管理服务（FR-01）。
 * <p>要点：口令 AES-GCM 加密落库、接口层掩码返回、全量变更历史、真实连通性测试、类型字典。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceService {

    private final DataSourceRepository sourceRepository;
    private final DataSourceLogRepository logRepository;
    private final ConnectorTypeRepository connectorTypeRepository;
    private final DeptNameResolver deptNameResolver;
    private final ConnectivityService connectivityService;

    @Value("${share.crypto.key:share-exchange-local-crypto-key-change-me}")
    private String cryptoKey;

    private volatile SecretKeySpec secretKey;

    // ==================== 查询 ====================

    /** 分页查询数据源。 */
    public Page<DataSourceVO> page(DataSourceQuery query) {
        Pageable pageable = PageRequest.of(Math.max(query.getPageNum(), 1) - 1, Math.max(query.getPageSize(), 1),
                Sort.by(Sort.Direction.DESC, "updateTime").and(Sort.by(Sort.Direction.DESC, "id")));
        return sourceRepository.findAll(buildSpec(query), pageable).map(DataSourceVO::from);
    }

    /** 数据源详情（口令掩码）。 */
    public DataSourceVO detail(Long id) {
        return DataSourceVO.from(requireSource(id));
    }

    /** 变更历史（分页）。 */
    public Page<DataSourceLogVO> history(Long id, int pageNum, int pageSize) {
        requireSource(id);
        Pageable pageable = PageRequest.of(Math.max(pageNum, 1) - 1, Math.max(pageSize, 1));
        return logRepository.findBySourceIdAndDeletedFalseOrderByCreateTimeDesc(id, pageable)
                .map(DataSourceLogVO::from);
    }

    /** 类型字典（优先取 ds_connector_type 表，未初始化时用内置定义）。 */
    public List<Map<String, Object>> types() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<ConnectorType> types = connectorTypeRepository.findByDeletedFalseOrderBySortNoAsc();
        if (!types.isEmpty()) {
            for (ConnectorType type : types) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", type.getTypeCode());
                item.put("typeCode", type.getTypeCode());
                item.put("name", type.getTypeName());
                item.put("typeName", type.getTypeName());
                item.put("category", type.getCategory());
                item.put("defaultPort", type.getDefaultPort());
                item.put("driverClass", type.getDriverClass());
                item.put("urlTemplate", type.getUrlTemplate());
                item.put("requireHost", type.getRequireHost());
                item.put("requirePath", type.getRequirePath());
                item.put("description", type.getDescription());
                item.put("status", type.getStatus());
                result.add(item);
            }
            return result;
        }
        for (SourceTypes.SourceTypeDef def : SourceTypes.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", def.getCode());
            item.put("typeCode", def.getCode());
            item.put("name", def.getName());
            item.put("typeName", def.getName());
            item.put("category", def.getCategory());
            item.put("defaultPort", def.getDefaultPort());
            item.put("driverClass", def.getDriverClass());
            item.put("urlTemplate", def.getUrlTemplate());
            item.put("requireHost", def.isRequireHost());
            item.put("requirePath", def.isRequirePath());
            item.put("description", def.getDescription());
            item.put("status", "ENABLED");
            result.add(item);
        }
        return result;
    }

    // ==================== 变更 ====================

    /** 新增数据源。 */
    @Transactional
    public DataSourceVO create(DataSourceDTO dto) {
        String code = normalizeCode(dto.getSourceCode());
        if (sourceRepository.findBySourceCodeAndDeletedFalse(code).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "数据源编码已存在：" + code);
        }
        String type = normalizeType(dto.getSourceType());
        DataSource entity = new DataSource();
        entity.setSourceCode(code);
        applyFields(entity, dto, type);
        entity.setStatus(normalizeSourceStatus(dto.getStatus(), "ENABLED"));
        if (entity.getSecretLevel() == null) {
            entity.setSecretLevel(1);
        }
        DataSource saved = sourceRepository.save(entity);
        writeLog(saved, "CREATE", "新增数据源", "编码=" + code + "，类型=" + type, "SUCCESS");
        log.info("数据源已登记: {} ({})", code, saved.getSourceName());
        return DataSourceVO.from(saved);
    }

    /** 修改数据源（口令为空或掩码时保持原密文）。 */
    @Transactional
    public DataSourceVO update(Long id, DataSourceDTO dto) {
        DataSource entity = requireSource(id);
        List<String> changes = new ArrayList<>();
        String newCode = dto.getSourceCode() == null || dto.getSourceCode().isBlank()
                ? entity.getSourceCode() : normalizeCode(dto.getSourceCode());
        if (!newCode.equalsIgnoreCase(entity.getSourceCode())) {
            sourceRepository.findBySourceCodeAndDeletedFalse(newCode).ifPresent(other -> {
                throw new BizException(ErrorCode.CONFLICT, "数据源编码已存在：" + newCode);
            });
            changes.add("编码 " + entity.getSourceCode() + "→" + newCode);
            entity.setSourceCode(newCode);
        }
        String type = normalizeType(dto.getSourceType() != null ? dto.getSourceType() : entity.getSourceType());
        compareAndSet(changes, "名称", entity.getSourceName(),
                dto.getSourceName() != null && !dto.getSourceName().isBlank() ? dto.getSourceName() : entity.getSourceName(),
                entity::setSourceName);
        compareAndSet(changes, "类型", entity.getSourceType(), type, entity::setSourceType);
        applyFields(entity, dto, type);
        // 状态：允许随编辑一起提交（缺省保持原状态）
        entity.setStatus(normalizeSourceStatus(dto.getStatus(), entity.getStatus()));
        DataSource saved = sourceRepository.save(entity);
        writeLog(saved, "UPDATE", "修改数据源", changes.isEmpty() ? "提交修改（字段无变化）" : String.join("；", changes),
                "SUCCESS");
        return DataSourceVO.from(saved);
    }

    /** 删除数据源（逻辑删除，保留变更历史）。 */
    @Transactional
    public void delete(Long id) {
        DataSource entity = requireSource(id);
        entity.setDeleted(true);
        sourceRepository.save(entity);
        writeLog(entity, "DELETE", "删除数据源", "逻辑删除数据源 " + entity.getSourceCode(), "SUCCESS");
    }

    /** 启用/停用。 */
    @Transactional
    public DataSourceVO changeStatus(Long id, StatusRequest request) {
        DataSource entity = requireSource(id);
        String status = normalizeSourceStatus(request.getStatus(), null);
        if (status == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能为 ENABLED 或 DISABLED");
        }
        String before = entity.getStatus();
        if (status.equalsIgnoreCase(before == null ? "" : before)) {
            return DataSourceVO.from(entity);
        }
        entity.setStatus(status);
        DataSource saved = sourceRepository.save(entity);
        String detail = "状态 " + before + "→" + status
                + (request.getReason() == null || request.getReason().isBlank() ? "" : "（" + request.getReason() + "）");
        writeLog(saved, "STATUS", "ENABLED".equals(status) ? "启用数据源" : "停用数据源", detail, "SUCCESS");
        return DataSourceVO.from(saved);
    }

    /** 连通性测试：真实探测并把结果回写到数据源与变更历史。 */
    @Transactional
    public SourceTestResult test(Long id) {
        DataSource entity = requireSource(id);
        SourceTestResult result = connectivityService.test(entity);
        entity.setLastTestTime(LocalDateTime.now());
        String summary = (result.isSuccess() ? "SUCCESS：" : "FAIL：") + result.getMessage();
        entity.setLastTestResult(truncate(summary, 256));
        sourceRepository.save(entity);
        writeLog(entity, "TEST", "连通性测试", result.getMessage(), result.isSuccess() ? "SUCCESS" : "FAIL");
        return result;
    }

    // ==================== 内部方法 ====================

    private DataSource requireSource(Long id) {
        return sourceRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.DATASOURCE_NOT_FOUND));
    }

    private Specification<DataSource> buildSpec(DataSourceQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("deleted")));
            if (hasText(query.getKeyword())) {
                String like = "%" + query.getKeyword().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("sourceCode")), like),
                        cb.like(cb.lower(root.get("sourceName")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("host"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("owner"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("remark"), "")), like)));
            }
            if (hasText(query.getType())) {
                predicates.add(cb.equal(root.get("sourceType"), query.getType().trim().toUpperCase(Locale.ROOT)));
            }
            if (hasText(query.getStatus())) {
                predicates.add(cb.equal(root.get("status"), query.getStatus().trim().toUpperCase(Locale.ROOT)));
            }
            if (query.getDeptId() != null) {
                predicates.add(cb.equal(root.get("deptId"), query.getDeptId()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 公共字段写入（含口令加密、部门名称回填、JDBC 串补全、扩展配置序列化）。 */
    private void applyFields(DataSource entity, DataSourceDTO dto, String type) {
        entity.setSourceType(type);
        if (dto.getSourceName() != null && !dto.getSourceName().isBlank()) {
            entity.setSourceName(dto.getSourceName().trim());
        }
        entity.setHost(trimToNull(dto.getHost()));
        entity.setPort(dto.getPort());
        entity.setDbName(trimToNull(dto.getDbName()));
        entity.setUsername(trimToNull(dto.getUsername()));
        entity.setJdbcUrl(trimToNull(dto.getJdbcUrl()));
        entity.setFilePath(trimToNull(dto.getFilePath()));
        entity.setApiUrl(trimToNull(dto.getApiUrl()));
        entity.setExtraConfig(normalizeExtraConfig(dto.getExtraConfig()));
        if (dto.getDeptId() != null) {
            entity.setDeptId(dto.getDeptId());
        }
        entity.setDeptName(deptNameResolver.resolveOrKeep(entity.getDeptId(),
                dto.getDeptName() != null && !dto.getDeptName().isBlank() ? dto.getDeptName().trim() : entity.getDeptName()));
        entity.setOwner(trimToNull(dto.getOwner()));
        if (dto.getSecretLevel() != null) {
            entity.setSecretLevel(normalizeSecretLevel(dto.getSecretLevel()));
        }
        entity.setRemark(trimToNull(dto.getRemark()));
        // 口令：仅当传入新明文（或非掩码密文位）时才更新
        String plain = resolvePlainPassword(dto);
        if (plain != null) {
            entity.setPasswordCipher(encrypt(plain));
        }
        // JDBC 串：数据库类未填写时按类型模板补全
        if (entity.getJdbcUrl() == null && SourceTypes.isDatabase(type)
                && entity.getHost() != null && entity.getPort() != null) {
            String template = SourceTypes.of(type).getUrlTemplate();
            if (template != null && !template.isBlank()) {
                entity.setJdbcUrl(template.replace("{host}", entity.getHost())
                        .replace("{port}", String.valueOf(entity.getPort()))
                        .replace("{dbName}", entity.getDbName() == null ? "" : entity.getDbName()));
            }
        }
    }

    /** 解析入参中的新口令；未提供或为掩码时返回 null 表示保持原值。 */
    private String resolvePlainPassword(DataSourceDTO dto) {
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            return dto.getPassword();
        }
        String cipher = dto.getPasswordCipher();
        if (cipher != null && !cipher.isBlank() && !DataSourceVO.MASK.equals(cipher.trim())) {
            return cipher;
        }
        return null;
    }

    private String normalizeExtraConfig(Object extraConfig) {
        if (extraConfig == null) {
            return null;
        }
        if (extraConfig instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            // 非 JSON 的纯文本按 JSON 字符串包装，避免前端提交非法 JSON
            try {
                JsonUtil.mapper().readTree(trimmed);
                return trimmed;
            } catch (Exception e) {
                return JsonUtil.toJson(trimmed);
            }
        }
        if (extraConfig instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        if (extraConfig instanceof Collection<?> collection && collection.isEmpty()) {
            return null;
        }
        return JsonUtil.toJson(extraConfig);
    }

    private String encrypt(String plain) {
        return CryptoUtil.encrypt(plain, key());
    }

    private SecretKeySpec key() {
        if (secretKey == null) {
            secretKey = CryptoUtil.deriveKey(cryptoKey);
        }
        return secretKey;
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据源编码不能为空");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据源类型不能为空");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!SourceTypes.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的数据源类型：" + type);
        }
        return normalized;
    }

    private String normalizeSourceStatus(String status, String defaultValue) {
        if (status == null || status.isBlank()) {
            return defaultValue;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!"ENABLED".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能为 ENABLED 或 DISABLED");
        }
        return normalized;
    }

    private Integer normalizeSecretLevel(Integer level) {
        if (level == null) {
            return null;
        }
        if (level < 1 || level > 4) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密级只能为 1~4");
        }
        return level;
    }

    /** 写入一条变更历史。 */
    private void writeLog(DataSource source, String action, String actionDesc, String detail, String result) {
        DataSourceLog entity = new DataSourceLog();
        entity.setSourceId(source.getId());
        entity.setSourceCode(source.getSourceCode());
        entity.setSourceName(source.getSourceName());
        entity.setAction(action);
        entity.setActionDesc(actionDesc);
        entity.setDetail(truncate(detail, 2000));
        entity.setResult(result);
        logRepository.save(entity);
    }

    private void compareAndSet(List<String> changes, String label, String before, String after,
                               java.util.function.Consumer<String> setter) {
        if (!Objects.equals(before, after)) {
            changes.add(label + " " + before + "→" + after);
            setter.accept(after);
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

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
