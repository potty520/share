package com.share.ops.audit.service;

import com.share.common.util.JsonUtil;
import com.share.ops.audit.entity.AudOperationLog;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Elasticsearch 审计索引与检索（HTTP 直连，避免额外客户端依赖）。 */
@Slf4j
@Service
public class ElasticsearchAuditService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${share.search.elasticsearch.enabled:false}")
    private boolean enabled;

    @Value("${share.search.elasticsearch.url:http://127.0.0.1:9200}")
    private String url;

    @Value("${share.search.elasticsearch.index:share-audit}")
    private String index;

    @Value("${share.search.elasticsearch.username:}")
    private String username;

    @Value("${share.search.elasticsearch.password:}")
    private String password;

    private volatile boolean ready;
    private volatile String lastError;

    @PostConstruct
    public void init() {
        if (!enabled) {
            return;
        }
        try {
            HttpResponse<String> response = request("PUT", "/" + index,
                    "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
                            + "\"mappings\":{\"properties\":{"
                            + "\"operatorName\":{\"type\":\"keyword\"},\"action\":{\"type\":\"keyword\"},"
                            + "\"module\":{\"type\":\"keyword\"},\"result\":{\"type\":\"keyword\"},"
                            + "\"operatorId\":{\"type\":\"long\"},\"costMs\":{\"type\":\"long\"},"
                            + "\"operateTime\":{\"type\":\"date\"},\"detail\":{\"type\":\"text\"},"
                            + "\"targetId\":{\"type\":\"keyword\"},\"uri\":{\"type\":\"text\"}}}}");
            if (response.statusCode() / 100 != 2 && response.statusCode() != 400) {
                throw new IllegalStateException("ES index " + response.statusCode());
            }
            ready = true;
            log.info("Elasticsearch 审计检索已启用 url={} index={}", url, index);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Elasticsearch 初始化失败，审计检索回退 MySQL：{}", e.getMessage());
        }
    }

    public boolean available() {
        return enabled && ready;
    }

    public void index(AudOperationLog entity) {
        if (!available() || entity == null || entity.getId() == null) {
            return;
        }
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", entity.getId());
            doc.put("operatorId", entity.getOperatorId());
            doc.put("operatorName", entity.getOperatorName());
            doc.put("roleCode", entity.getRoleCode());
            doc.put("module", entity.getModule());
            doc.put("action", entity.getAction());
            doc.put("targetType", entity.getTargetType());
            doc.put("targetId", entity.getTargetId());
            doc.put("detail", entity.getDetail());
            doc.put("result", entity.getResult());
            doc.put("errorMsg", entity.getErrorMsg());
            doc.put("ip", entity.getIp());
            doc.put("method", entity.getRequestMethod());
            doc.put("uri", entity.getUri());
            doc.put("costMs", entity.getCostMs());
            doc.put("operateTime", entity.getOperateTime() == null ? null : entity.getOperateTime().toString());
            doc.put("createTime", entity.getCreateTime() == null ? null : entity.getCreateTime().toString());
            HttpResponse<String> response = request("PUT", "/" + index + "/_doc/" + entity.getId(), JsonUtil.toJson(doc));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("ES index " + response.statusCode());
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Elasticsearch 审计索引失败 id={}: {}", entity.getId(), e.getMessage());
        }
    }

    public SearchResult search(String operator, String action, String module, String result,
                               String keyword, Long operatorId, Long minCostMs,
                               LocalDateTime from, LocalDateTime to, int pageNum, int pageSize) {
        List<Object> must = new ArrayList<>();
        if (hasText(operator)) {
            must.add(Map.of("term", Map.of("operatorName", operator)));
        }
        if (hasText(action)) {
            must.add(Map.of("term", Map.of("action", action)));
        }
        if (hasText(module)) {
            must.add(Map.of("term", Map.of("module", module)));
        }
        if (hasText(result)) {
            must.add(Map.of("term", Map.of("result", result)));
        }
        if (operatorId != null) {
            must.add(Map.of("term", Map.of("operatorId", operatorId)));
        }
        if (minCostMs != null) {
            must.add(Map.of("range", Map.of("costMs", Map.of("gte", minCostMs))));
        }
        if (from != null || to != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (from != null) {
                range.put("gte", from.toString());
            }
            if (to != null) {
                range.put("lte", to.toString());
            }
            must.add(Map.of("range", Map.of("operateTime", range)));
        }
        if (hasText(keyword)) {
            must.add(Map.of("multi_match", Map.of(
                    "query", keyword,
                    "fields", List.of("operatorName", "action", "module", "detail", "targetId", "uri"))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", Math.max(0, (pageNum - 1) * pageSize));
        body.put("size", pageSize);
        body.put("query", must.isEmpty() ? Map.of("match_all", Map.of())
                : Map.of("bool", Map.of("must", must)));
        body.put("sort", List.of(Map.of("operateTime", Map.of("order", "desc", "unmapped_type", "date"))));
        try {
            HttpResponse<String> response = request("POST", "/" + index + "/_search", JsonUtil.toJson(body));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("ES search " + response.statusCode());
            }
            Map<String, Object> json = JsonUtil.fromJson(response.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> hits = map(json.get("hits"));
            long total = totalOf(hits.get("total"));
            List<Map<String, Object>> items = new ArrayList<>();
            Object rows = hits.get("hits");
            if (rows instanceof List<?> list) {
                for (Object row : list) {
                    Map<String, Object> hit = map(row);
                    Map<String, Object> source = map(hit.get("_source"));
                    source.putIfAbsent("id", hit.get("_id"));
                    items.add(source);
                }
            }
            return new SearchResult(items, total, false);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Elasticsearch 审计检索失败，回退 MySQL：{}", e.getMessage());
            return new SearchResult(List.of(), 0, true);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("available", available());
        result.put("url", enabled ? url : null);
        result.put("index", enabled ? index : null);
        result.put("lastError", lastError);
        return result;
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json");
        if (username != null && !username.isBlank()) {
            String auth = java.util.Base64.getEncoder().encodeToString(
                    (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + auth);
        }
        builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private long totalOf(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Map<?, ?> m && m.get("value") instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record SearchResult(List<Map<String, Object>> items, long total, boolean fallback) {
    }
}
