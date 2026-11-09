package com.share.share.service;

import com.share.common.api.ErrorCode;
import com.share.common.constant.PermissionConstants;
import com.share.common.exception.BizException;
import com.share.common.security.LoginUser;
import com.share.common.security.UserContext;
import com.share.common.util.JsonUtil;
import com.share.share.constant.ShareConstants;
import com.share.share.dto.ApiSaveRequest;
import com.share.share.dto.ApiTestRequest;
import com.share.share.entity.ShareApi;
import com.share.share.entity.ShareApiParam;
import com.share.share.repository.ShareApiParamRepository;
import com.share.share.repository.ShareApiRepository;
import com.share.share.repository.ShareApiVersionRepository;
import com.share.share.repository.ShareCredentialRepository;
import com.share.share.entity.ShareApiVersion;
import com.share.share.support.CrossDomainQueryService;
import com.share.share.support.PageResult;
import com.share.share.support.ShareUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享 API 全生命周期服务（FR-07）。
 * <p>创建 → 配置（数据源绑定/查询参数/返回字段）→ 测试 → 上线（生成网关路由配置）→
 * 变更 → 下线；并支持在线调试、自动文档与计量统计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareApiService {

    private final ShareApiRepository apiRepository;
    private final ShareApiParamRepository paramRepository;
    private final ApisixGatewayService apisixGatewayService;
    private final ShareApiVersionRepository versionRepository;
    private final ShareCredentialRepository credentialRepository;
    private final DataQueryService dataQueryService;
    private final ShareCallStatService callStatService;
    private final CrossDomainQueryService crossDomainQueryService;

    /** 分页查询（支持 keyword 模糊匹配编码/名称、status 过滤、catalogId 过滤）。 */
    public PageResult<ShareApi> list(String keyword, String status, Long catalogId, int pageNum, int pageSize) {
        Specification<ShareApi> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("apiName")), like),
                        cb.like(cb.lower(root.get("apiCode")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("catalogName"), "")), like)));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            if (catalogId != null) {
                ps.add(cb.equal(root.get("catalogId"), catalogId));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        int num = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Page<ShareApi> page = apiRepository.findAll(spec,
                PageRequest.of(num - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.of(page.getContent(), page.getTotalElements(), num, size);
    }

    public ShareApi findOrThrow(Long id) {
        return apiRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_API_NOT_FOUND));
    }

    public ShareApi findByCode(String apiCode) {
        return apiRepository.findByApiCodeAndDeletedFalse(apiCode)
                .orElseThrow(() -> new BizException(ErrorCode.SHARE_API_NOT_FOUND));
    }

    /** 详情：扁平化实体字段 + 参数列表 + 凭据数 + 路由配置对象。 */
    public Map<String, Object> detail(Long id) {
        ShareApi api = findOrThrow(id);
        Map<String, Object> data = toMap(api);
        List<ShareApiParam> params = dataQueryService.paramsOf(id);
        data.put("params", params);
        data.put("paramCount", params.size());
        data.put("credentialCount", credentialRepository.countByApiIdAndDeletedFalseAndStatus(
                id, ShareConstants.CRED_ACTIVE));
        data.put("maskedFieldList", ShareUtils.split(api.getMaskFields()));
        data.put("returnFieldList", ShareUtils.split(api.getReturnFields()));
        data.put("requiredLevel", crossDomainQueryService.catalogLevel(api.getCatalogId()));
        data.put("routeConfigObject", parseJson(api.getRouteConfig()));
        data.put("apiDocUrl", "/api/share/v1/apis/" + id + "/doc");
        data.put("openUrl", "/api/open/v1/" + api.getApiCode());
        data.put("versionCount", versionRepository.findByApiIdAndDeletedFalseOrderByVersionNoDesc(id).size());
        return data;
    }

    /** 新建共享 API（默认草稿态，需显式上线）。 */
    @Transactional
    public Map<String, Object> create(ApiSaveRequest req) {
        String code = req.apiCode().trim().toUpperCase();
        if (code.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "apiCode 长度不能超过 64 个字符");
        }
        if (apiRepository.existsByApiCodeAndDeletedFalse(code)) {
            throw new BizException(ErrorCode.CONFLICT, "共享服务编码已存在: " + code);
        }
        ShareApi api = new ShareApi();
        api.setApiCode(code);
        apply(api, req);
        api.setStatus(ShareConstants.API_DRAFT);
        api.setCallCount(0L);
        LoginUser user = UserContext.get();
        if (api.getOwner() == null || api.getOwner().isBlank()) {
            api.setOwner(user == null ? "system" : user.getUsername());
        }
        if (api.getDeptId() == null && user != null) {
            api.setDeptId(user.getDeptId());
        }
        ShareApi saved = apiRepository.save(api);
        syncParams(saved.getId(), req.paramsJson());
        log.info("创建共享服务 code={} id={}", saved.getApiCode(), saved.getId());
        return detail(saved.getId());
    }

    /** 修改共享 API（上线态修改后仍保持上线，路由配置在下次上线时刷新）。 */
    @Transactional
    public Map<String, Object> update(Long id, ApiSaveRequest req) {
        ShareApi api = findOrThrow(id);
        String code = req.apiCode().trim().toUpperCase();
        if (code.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "apiCode 长度不能超过 64 个字符");
        }
        if (!code.equals(api.getApiCode()) && apiRepository.existsByApiCodeAndDeletedFalse(code)) {
            throw new BizException(ErrorCode.CONFLICT, "共享服务编码已存在: " + code);
        }
        api.setApiCode(code);
        apply(api, req);
        apiRepository.save(api);
        syncParams(id, req.paramsJson());
        if (ShareConstants.API_ONLINE.equals(api.getStatus())) {
            // 上线态修改后刷新网关路由配置（返回字段/参数/QPS 变更需立即生效）
            api.setRouteConfig(buildRouteConfig(api, true));
            apiRepository.save(api);
            syncApisix(api, true);
        }
        log.info("修改共享服务 id={} code={}", id, api.getApiCode());
        return detail(id);
    }

    /** 删除（逻辑删除）：上线中的服务必须先下线，避免产生孤儿路由。 */
    @Transactional
    public void delete(Long id) {
        ShareApi api = findOrThrow(id);
        if (ShareConstants.API_ONLINE.equals(api.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "共享服务已上线，请先下线再删除");
        }
        api.setDeleted(true);
        apiRepository.save(api);
        log.info("删除共享服务 id={} code={}", id, api.getApiCode());
    }

    /** 上线：校验挂接信息并生成/更新网关路由配置。 */
    @Transactional
    public Map<String, Object> online(Long id) {
        ShareApi api = findOrThrow(id);
        if (api.getSourceTable() == null || api.getSourceTable().isBlank()) {
            throw new BizException(ErrorCode.CONFLICT, "请先配置挂接数据表再上线");
        }
        if (ShareUtils.split(api.getReturnFields()).isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "请先配置返回字段再上线");
        }
        if (ShareConstants.API_ONLINE.equals(api.getStatus())) {
            // 重复上线幂等：只刷新路由配置
            api.setRouteConfig(buildRouteConfig(api, true));
            apiRepository.save(api);
            syncApisix(api, true);
            return detail(id);
        }
        boolean firstPublish = versionRepository.findTopByApiIdAndDeletedFalseOrderByVersionNoDesc(id).isEmpty();
        int versionNo = firstPublish ? Math.max(1, api.getVersionNo() == null ? 1 : api.getVersionNo())
                : nextVersionNo(api);
        api.setVersionNo(versionNo);
        api.setApiVersion("v" + versionNo);
        api.setStatus(ShareConstants.API_ONLINE);
        api.setOnlineTime(LocalDateTime.now());
        api.setRouteConfig(buildRouteConfig(api, true));
        apiRepository.save(api);
        saveSnapshot(api, versionNo, "上线发布", ShareConstants.API_ONLINE);
        syncApisix(api, true);
        log.info("共享服务上线 code={} route={}", api.getApiCode(), api.getRouteConfig());
        return detail(id);
    }

    /** 下线：路由置为禁用态，凭据不可再调用。 */
    @Transactional
    public Map<String, Object> offline(Long id) {
        ShareApi api = findOrThrow(id);
        if (!ShareConstants.API_ONLINE.equals(api.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "共享服务未处于上线状态");
        }
        api.setStatus(ShareConstants.API_OFFLINE);
        api.setRouteConfig(buildRouteConfig(api, false));
        apiRepository.save(api);
        syncApisix(api, false);
        log.info("共享服务下线 code={}", api.getApiCode());
        return detail(id);
    }

    /** 历史版本列表（含版本号、标签、发布说明与发布时间）。 */
    public List<Map<String, Object>> versions(Long id) {
        findOrThrow(id);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ShareApiVersion v : versionRepository.findByApiIdAndDeletedFalseOrderByVersionNoDesc(id)) {
            list.add(versionView(v));
        }
        return list;
    }

    /** 手工冻结当前配置为新版本（可用于上线态变更留痕）。 */
    @Transactional
    public Map<String, Object> publishVersion(Long id, String note) {
        ShareApi api = findOrThrow(id);
        int versionNo = nextVersionNo(api);
        api.setVersionNo(versionNo);
        api.setApiVersion("v" + versionNo);
        api.setVersionNote(ShareUtils.truncate(note == null || note.isBlank() ? "手工发布版本" : note, 255));
        if (ShareConstants.API_ONLINE.equals(api.getStatus())) {
            api.setRouteConfig(buildRouteConfig(api, true));
        }
        apiRepository.save(api);
        ShareApiVersion snapshot = saveSnapshot(api, versionNo, api.getVersionNote(), api.getStatus());
        if (ShareConstants.API_ONLINE.equals(api.getStatus())) {
            syncApisix(api, true);
        }
        log.info("共享服务发布新版本 id={} code={} version=v{}", id, api.getApiCode(), versionNo);
        return versionView(snapshot);
    }

    /** 回滚到指定历史版本：恢复配置并生成新的版本号（版本号只增不减）。 */
    @Transactional
    public Map<String, Object> rollback(Long id, Integer versionNo) {
        ShareApi api = findOrThrow(id);
        ShareApiVersion target = versionRepository.findByApiIdAndVersionNoAndDeletedFalse(id, versionNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "版本不存在: v" + versionNo));
        Map<String, Object> snapshot = JsonUtil.fromJson(target.getSnapshotJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        if (snapshot == null || snapshot.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "版本快照内容为空，无法回滚");
        }
        int newVersion = nextVersionNo(api);
        restore(api, snapshot);
        api.setVersionNo(newVersion);
        api.setApiVersion("v" + newVersion);
        api.setVersionNote("回滚自 v" + versionNo);
        if (ShareConstants.API_ONLINE.equals(api.getStatus())) {
            api.setRouteConfig(buildRouteConfig(api, true));
        }
        apiRepository.save(api);
        ShareApiVersion snapshotRow = saveSnapshot(api, newVersion, api.getVersionNote(), api.getStatus());
        if (ShareConstants.API_ONLINE.equals(api.getStatus())) {
            syncApisix(api, true);
        }
        log.info("共享服务回滚 id={} code={} 从 v{} 回滚为 v{}", id, api.getApiCode(), versionNo, newVersion);
        return versionView(snapshotRow);
    }

    private int nextVersionNo(ShareApi api) {
        int max = versionRepository.findTopByApiIdAndDeletedFalseOrderByVersionNoDesc(api.getId())
                .map(ShareApiVersion::getVersionNo).orElse(0);
        int current = api.getVersionNo() == null ? 0 : api.getVersionNo();
        return Math.max(max, current) + 1;
    }

    private ShareApiVersion saveSnapshot(ShareApi api, int versionNo, String note, String status) {
        ShareApiVersion version = new ShareApiVersion();
        version.setApiId(api.getId());
        version.setApiCode(api.getApiCode());
        version.setVersionNo(versionNo);
        version.setVersionLabel("v" + versionNo);
        version.setStatus(status);
        version.setNote(ShareUtils.truncate(note, 255));
        version.setEditor(UserContext.username());
        version.setPublishedTime(LocalDateTime.now());
        version.setSnapshotJson(JsonUtil.toJson(toMap(api)));
        return versionRepository.save(version);
    }

    @SuppressWarnings("unchecked")
    private void restore(ShareApi api, Map<String, Object> snapshot) {
        if (snapshot.get("apiCode") != null) {
            String code = String.valueOf(snapshot.get("apiCode")).trim().toUpperCase();
            if (!code.equals(api.getApiCode()) && !apiRepository.existsByApiCodeAndDeletedFalse(code)) {
                api.setApiCode(code);
            }
        }
        api.setApiName(stringValue(snapshot.get("apiName"), api.getApiName()));
        api.setCatalogId(longValue(snapshot.get("catalogId")));
        api.setCatalogName(stringValue(snapshot.get("catalogName"), api.getCatalogName()));
        api.setSourceId(longValue(snapshot.get("sourceId")));
        api.setSourceTable(stringValue(snapshot.get("sourceTable"), api.getSourceTable()));
        api.setHttpMethod(stringValue(snapshot.get("httpMethod"), api.getHttpMethod()));
        api.setParamsJson(stringValue(snapshot.get("paramsJson"), api.getParamsJson()));
        api.setReturnFields(stringValue(snapshot.get("returnFields"), api.getReturnFields()));
        api.setMaskFields(stringValue(snapshot.get("maskFields"), api.getMaskFields()));
        if (snapshot.get("qpsLimit") instanceof Number n) {
            api.setQpsLimit(n.intValue());
        }
        api.setOwner(stringValue(snapshot.get("owner"), api.getOwner()));
        api.setRemark(stringValue(snapshot.get("remark"), api.getRemark()));
        String status = stringValue(snapshot.get("status"), api.getStatus());
        if (List.of(ShareConstants.API_DRAFT, ShareConstants.API_ONLINE, ShareConstants.API_OFFLINE).contains(status)) {
            api.setStatus(status);
        }
        syncParams(api.getId(), api.getParamsJson());
    }

    private Map<String, Object> versionView(ShareApiVersion v) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", v.getId());
        item.put("apiId", v.getApiId());
        item.put("apiCode", v.getApiCode());
        item.put("versionNo", v.getVersionNo());
        item.put("versionLabel", v.getVersionLabel());
        item.put("status", v.getStatus());
        item.put("note", v.getNote());
        item.put("editor", v.getEditor());
        item.put("publishedTime", v.getPublishedTime());
        return item;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 在线调试：按参数真实查询挂接表数据，固定做脱敏并记录一次调用统计（channel=DEBUG）。
     * <p>查询失败也要保留失败计量，因此业务异常不触发回滚。</p>
     */
    @Transactional(noRollbackFor = BizException.class)
    public Map<String, Object> test(Long id, ApiTestRequest req) {
        ShareApi api = findOrThrow(id);
        int pageNum = req == null || req.pageNum() == null ? 1 : req.pageNum();
        int pageSize = req == null || req.pageSize() == null ? 10 : req.pageSize();
        Map<String, Object> params = req == null ? Map.of() : req.safeParams();
        long start = System.currentTimeMillis();
        try {
            DataQueryService.QueryResult result = dataQueryService.query(api, params, pageNum, pageSize, true);
            callStatService.record(api, null, ShareConstants.CHANNEL_DEBUG, result.costMs(),
                    ShareConstants.STAT_SUCCESS, result.rows().size(), "127.0.0.1", null);
            touchCall(api);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("apiId", api.getId());
            data.put("apiCode", api.getApiCode());
            data.put("apiName", api.getApiName());
            data.put("requestParams", params);
            data.put("pageNum", result.pageNum());
            data.put("pageSize", result.pageSize());
            data.put("total", result.total());
            data.put("list", result.rows());
            data.put("masked", true);
            data.put("maskedFields", result.maskedFields());
            data.put("costMs", result.costMs());
            data.put("callTime", LocalDateTime.now());
            return data;
        } catch (RuntimeException e) {
            callStatService.record(api, null, ShareConstants.CHANNEL_DEBUG,
                    System.currentTimeMillis() - start, ShareConstants.STAT_FAIL, 0, "127.0.0.1", e.getMessage());
            throw e;
        }
    }

    /** 自动生成 OpenAPI 3 片段（paths/parameters/schema）。 */
    public Map<String, Object> doc(Long id) {
        ShareApi api = findOrThrow(id);
        List<ShareApiParam> params = dataQueryService.paramsOf(id);
        List<String> returnFields = ShareUtils.split(api.getReturnFields());
        List<String> maskFields = ShareUtils.split(api.getMaskFields());
        List<Object> parameters = new ArrayList<>();
        for (ShareApiParam p : params) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", openApiType(p.getParamType()));
            if (p.getExampleValue() != null) {
                schema.put("example", p.getExampleValue());
            }
            if (p.getDefaultValue() != null) {
                schema.put("default", p.getDefaultValue());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", p.getParamName());
            item.put("in", "query");
            item.put("required", Boolean.TRUE.equals(p.getRequired()));
            item.put("description", p.getParamDesc());
            item.put("schema", schema);
            parameters.add(item);
        }
        Map<String, Object> pageNumParam = Map.of(
                "name", "pageNum", "in", "query", "required", false,
                "description", "页码，从 1 开始",
                "schema", Map.of("type", "integer", "default", 1));
        Map<String, Object> pageSizeParam = Map.of(
                "name", "pageSize", "in", "query", "required", false,
                "description", "每页条数，最大 200",
                "schema", Map.of("type", "integer", "default", 10));
        parameters.add(pageNumParam);
        parameters.add(pageSizeParam);

        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : returnFields) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "string");
            if (maskFields.contains(field)) {
                prop.put("description", "敏感字段：授权密级不足时返回脱敏值");
                prop.put("x-masked", true);
            }
            properties.put(field, prop);
        }
        String method = api.getHttpMethod() == null ? "get" : api.getHttpMethod().toLowerCase();
        Map<String, Object> schemaObj = new LinkedHashMap<>();
        schemaObj.put("type", "object");
        schemaObj.put("properties", properties);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("tags", List.of(api.getCatalogName() == null ? "共享服务" : api.getCatalogName()));
        operation.put("summary", api.getApiName());
        operation.put("description", "共享服务编码 " + api.getApiCode() + "，需在请求头携带 X-App-Key / X-App-Secret");
        operation.put("operationId", api.getApiCode());
        operation.put("parameters", parameters);
        operation.put("security", List.of(Map.of("AppKeyAuth", List.of())));
        operation.put("responses", Map.of("200", Map.of(
                "description", "调用成功",
                "content", Map.of("application/json", Map.of(
                        "schema", Map.of("$ref", "#/components/schemas/" + api.getApiCode() + "Result"))))));
        operation.put("x-qps-limit", api.getQpsLimit());
        operation.put("x-mask-fields", maskFields);
        operation.put("x-status", api.getStatus());

        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("/api/open/v1/" + api.getApiCode(), Map.of(method, operation));

        Map<String, Object> resultSchema = new LinkedHashMap<>();
        resultSchema.put("type", "object");
        resultSchema.put("properties", Map.of(
                "code", Map.of("type", "integer", "example", 0),
                "message", Map.of("type", "string", "example", "success"),
                "data", Map.of("type", "object", "properties", Map.of(
                        "list", Map.of("type", "array", "items", schemaObj),
                        "total", Map.of("type", "integer"),
                        "masked", Map.of("type", "boolean")))));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.1");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", api.getApiName());
        info.put("description", "共享交换系统自动生成的服务文档（FR-07）");
        info.put("version", "1.0.0");
        info.put("x-api-code", api.getApiCode());
        doc.put("info", info);
        doc.put("servers", List.of(Map.of("url", "/api/open/v1", "description", "共享服务开放网关")));
        doc.put("paths", paths);
        doc.put("components", Map.of(
                "schemas", Map.of(api.getApiCode() + "Result", resultSchema),
                "securitySchemes", Map.of("AppKeyAuth", Map.of(
                        "type", "apiKey", "in", "header", "name", "X-App-Key",
                        "description", "配套请求头 X-App-Secret 用于密钥校验"))));
        // 元信息（catalogId/catalogName 可能为空，不能用 Map.of）
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("apiId", api.getId());
        meta.put("apiCode", api.getApiCode());
        meta.put("catalogId", api.getCatalogId());
        meta.put("catalogName", api.getCatalogName());
        meta.put("maskFields", maskFields);
        meta.put("returnFields", returnFields);
        meta.put("qpsLimit", api.getQpsLimit());
        meta.put("status", api.getStatus());
        meta.put("curlSample", "curl -H \"X-App-Key: <appKey>\" -H \"X-App-Secret: <appSecret>\" \""
                + "/api/open/v1/" + api.getApiCode() + "?pageNum=1&pageSize=10\"");
        doc.put("x-share-api", meta);
        return doc;
    }

    /** 累加调用次数与最近调用时间。 */
    @Transactional
    public void touchCall(ShareApi api) {
        api.setCallCount((api.getCallCount() == null ? 0L : api.getCallCount()) + 1);
        api.setLastCallTime(LocalDateTime.now());
        apiRepository.save(api);
    }

    // ==================== 内部实现 ====================

    /** 写入请求字段（按 MySQL 列长度截断，避免严格模式下的 Data too long 报错）。 */
    private void apply(ShareApi api, ApiSaveRequest req) {
        api.setApiName(ShareUtils.truncate(req.apiName().trim(), 128));
        api.setCatalogId(req.catalogId());
        api.setSourceId(req.sourceId());
        api.setSourceTable(ShareUtils.truncate(trimToNull(req.sourceTable()), 128));
        api.setHttpMethod(req.httpMethod() == null || req.httpMethod().isBlank()
                ? "GET" : ShareUtils.truncate(req.httpMethod().trim().toUpperCase(), 16));
        api.setParamsJson(trimToNull(req.paramsJson()));
        api.setReturnFields(trimToNull(req.returnFields()));
        api.setMaskFields(trimToNull(req.maskFields()));
        api.setQpsLimit(req.qpsLimit() == null || req.qpsLimit() <= 0 ? 100 : req.qpsLimit());
        api.setOwner(ShareUtils.truncate(trimToNull(req.owner()), 64));
        api.setDeptId(req.deptId());
        api.setRemark(ShareUtils.truncate(trimToNull(req.remark()), 500));
        String catalogName = trimToNull(req.catalogName());
        if (catalogName == null && req.catalogId() != null) {
            catalogName = crossDomainQueryService.findCatalog(req.catalogId())
                    .map(CrossDomainQueryService.CatalogRef::name).orElse(null);
        }
        api.setCatalogName(ShareUtils.truncate(catalogName, 128));
    }

    /** paramsJson → shr_api_param 规范化（先删后插，保证与定义一致）。 */
    @Transactional
    public void syncParams(Long apiId, String paramsJson) {
        paramRepository.deleteByApiId(apiId);
        if (paramsJson == null || paramsJson.isBlank()) {
            return;
        }
        List<Map<String, Object>> defs;
        try {
            defs = JsonUtil.fromJson(paramsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "paramsJson 不是合法的参数定义数组");
        }
        if (defs == null) {
            return;
        }
        int index = 0;
        for (Map<String, Object> def : defs) {
            if (def == null) {
                continue;
            }
            String name = firstString(def, "name", "paramName", "param", "field");
            if (name == null) {
                continue;
            }
            ShareApiParam param = new ShareApiParam();
            param.setApiId(apiId);
            param.setParamName(ShareUtils.truncate(name, 64));
            param.setParamType(ShareUtils.truncate(defaultString(firstString(def, "type", "paramType"), "string"), 32));
            param.setRequired(firstBool(def, "required", "must"));
            param.setDefaultValue(ShareUtils.truncate(firstString(def, "default", "defaultValue"), 128));
            param.setParamDesc(ShareUtils.truncate(firstString(def, "desc", "description", "paramDesc", "label"), 255));
            param.setExampleValue(ShareUtils.truncate(firstString(def, "example", "exampleValue"), 128));
            param.setColumnName(ShareUtils.truncate(firstString(def, "column", "columnName"), 64));
            param.setOp(ShareUtils.truncate(defaultString(firstString(def, "op", "operator"), "eq"), 16));
            param.setSortNo(index++);
            paramRepository.save(param);
        }
    }

    /** 生成 API 网关路由配置 JSON（FR-07：一个共享 API = 一条路由 + 插件集）。 */
    private String buildRouteConfig(ShareApi api, boolean enabled) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("routeId", "route_" + api.getApiCode());
        route.put("name", api.getApiName());
        route.put("uri", "/api/open/v1/" + api.getApiCode());
        route.put("methods", List.of(api.getHttpMethod() == null ? "GET" : api.getHttpMethod()));
        route.put("upstream", "platform-db://" + (api.getSourceTable() == null ? "" : api.getSourceTable()));
        route.put("enabled", enabled);

        List<Map<String, Object>> plugins = new ArrayList<>();
        plugins.add(Map.of("name", "app-key-auth", "config", Map.of(
                "headers", List.of("X-App-Key", "X-App-Secret"),
                "rejectCode", ErrorCode.SHARE_CREDENTIAL_INVALID.getCode())));
        plugins.add(Map.of("name", "limit-req", "config", Map.of(
                "key", "appKey",
                "rate", api.getQpsLimit() == null ? 100 : api.getQpsLimit(),
                "burst", (api.getQpsLimit() == null ? 100 : api.getQpsLimit()) * 2,
                "rejectCode", ErrorCode.SHARE_QPS_LIMITED.getCode())));
        plugins.add(Map.of("name", "prometheus", "config", Map.of("prefer_name", true)));
        plugins.add(Map.of("name", "dynamic-mask", "config", Map.of(
                "fields", ShareUtils.split(api.getMaskFields()),
                "mode", "by-grant-level",
                "requiredLevel", crossDomainQueryService.catalogLevel(api.getCatalogId()))));
        plugins.add(Map.of("name", "ip-acl", "config", Map.of(
                "whitelist", "credential.ipWhitelist",
                "blacklist", "credential.ipBlacklist",
                "order", "deny-first")));
        plugins.add(Map.of("name", "cluster-limit", "config", Map.of(
                "key", "appKey",
                "rate", api.getQpsLimit() == null ? 100 : api.getQpsLimit(),
                "scope", "cluster",
                "rejectCode", ErrorCode.SHARE_QPS_LIMITED.getCode())));
        plugins.add(Map.of("name", "circuit-breaker", "config", Map.of(
                "failureThreshold", 5,
                "windowSeconds", 60,
                "openSeconds", 30,
                "rejectCode", ErrorCode.SHARE_API_CIRCUIT_OPEN.getCode())));
        plugins.add(Map.of("name", "api-version", "config", Map.of(
                "header", "X-Api-Version",
                "current", api.getApiVersion() == null ? "v1" : api.getApiVersion())));
        List<String> paramNames = dataQueryService.paramsOf(api.getId()).stream()
                .map(ShareApiParam::getParamName).toList();
        plugins.add(Map.of("name", "param-validate", "config", Map.of("params", paramNames)));
        route.put("plugins", plugins);
        route.put("updateTime", LocalDateTime.now().toString());
        return JsonUtil.toJson(route);
    }

    private Map<String, Object> toMap(ShareApi api) {
        Map<String, Object> map = JsonUtil.mapper().convertValue(api,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        return new LinkedHashMap<>(map);
    }

    private void syncApisix(ShareApi api, boolean enabled) {
        ApisixGatewayService.SyncResult result = apisixGatewayService.sync(api, enabled);
        api.setGatewayRouteId(result.routeId());
        api.setGatewaySyncStatus(result.status());
        api.setGatewaySyncTime(LocalDateTime.now());
        api.setGatewayError(result.success() ? null : ShareUtils.truncate(result.message(), 480));
        apiRepository.save(api);
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return json;
        }
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                    String v = String.valueOf(e.getValue()).trim();
                    if (!v.isEmpty()) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    private static boolean firstBool(Map<String, Object> map, String... keys) {
        String v = firstString(map, keys);
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String openApiType(String paramType) {
        if (paramType == null) {
            return "string";
        }
        return switch (paramType.trim().toLowerCase()) {
            case "int", "integer", "long", "bigint", "decimal", "double", "number" -> "number";
            case "bool", "boolean" -> "boolean";
            case "date", "datetime", "time" -> "string";
            default -> "string";
        };
    }
}
