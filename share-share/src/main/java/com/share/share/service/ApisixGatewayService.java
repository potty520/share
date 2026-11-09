package com.share.share.service;

import com.share.common.util.JsonUtil;
import com.share.share.constant.ShareConstants;
import com.share.share.entity.ShareApi;
import com.share.share.repository.ShareApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** APISIX Admin API 同步：共享 API → 路由 + JWT 消费者 + 插件。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApisixGatewayService {

    private final ShareApiRepository apiRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${share.gateway.apisix.enabled:false}")
    private boolean enabled;

    @Value("${share.gateway.apisix.admin-url:http://127.0.0.1:9180}")
    private String adminUrl;

    @Value("${share.gateway.apisix.admin-key:share-apisix-admin-key}")
    private String adminKey;

    @Value("${share.gateway.apisix.upstream-host:127.0.0.1}")
    private String upstreamHost;

    @Value("${share.gateway.apisix.upstream-port:8080}")
    private int upstreamPort;

    @Value("${share.gateway.apisix.jwt-consumer:share_platform}")
    private String jwtConsumer;

    @Value("${share.gateway.apisix.jwt-secret:}")
    private String jwtSecret;

    public SyncResult sync(ShareApi api, boolean routeEnabled) {
        if (!enabled) {
            return new SyncResult(false, "DISABLED", routeIdOf(api), "APISIX 未启用");
        }
        String routeId = routeIdOf(api);
        try {
            ensureConsumer();
            if (routeEnabled) {
                putRoute(api, routeId);
            } else {
                deleteRoute(routeId);
            }
            String status = routeEnabled ? "SYNCED" : "DISABLED";
            return new SyncResult(true, status, routeId, routeEnabled ? "路由已下发" : "路由已下线");
        } catch (Exception e) {
            log.warn("APISIX 路由同步失败 code={}: {}", api.getApiCode(), e.getMessage());
            return new SyncResult(false, "FAILED", routeId, e.getMessage());
        }
    }

    public Map<String, Object> syncAll() {
        List<Map<String, Object>> items = new ArrayList<>();
        int synced = 0;
        int failed = 0;
        for (ShareApi api : apiRepository.findByDeletedFalseOrderByIdAsc()) {
            boolean shouldOnline = ShareConstants.API_ONLINE.equals(api.getStatus());
            SyncResult result = sync(api, shouldOnline);
            api.setGatewayRouteId(result.routeId());
            api.setGatewaySyncStatus(result.status());
            api.setGatewaySyncTime(java.time.LocalDateTime.now());
            api.setGatewayError(result.success() ? null : truncate(result.message(), 480));
            apiRepository.save(api);
            if (result.success()) {
                synced++;
            } else {
                failed++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("apiCode", api.getApiCode());
            item.put("enabled", shouldOnline);
            item.put("success", result.success());
            item.put("status", result.status());
            item.put("routeId", result.routeId());
            item.put("message", result.message());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("synced", synced);
        result.put("failed", failed);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("adminUrl", enabled ? adminUrl : null);
        result.put("upstream", enabled ? upstreamHost + ":" + upstreamPort : null);
        result.put("jwtConsumer", enabled ? jwtConsumer : null);
        result.put("routeCount", apiRepository.findByDeletedFalseOrderByIdAsc().size());
        if (enabled) {
            try {
                result.put("adminHealthy", get("/apisix/admin/routes").statusCode() / 100 == 2);
            } catch (Exception e) {
                result.put("adminHealthy", false);
                result.put("error", e.getMessage());
            }
        }
        return result;
    }

    private void putRoute(ShareApi api, String routeId) throws Exception {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("type", "roundrobin");
        upstream.put("scheme", "http");
        upstream.put("nodes", Map.of(upstreamHost + ":" + upstreamPort, 1));

        Map<String, Object> jwt = Map.of();
        Map<String, Object> limit = new LinkedHashMap<>();
        int qps = api.getQpsLimit() == null || api.getQpsLimit() <= 0 ? 100 : api.getQpsLimit();
        limit.put("rate", qps);
        limit.put("burst", qps * 2);
        limit.put("key_type", "var");
        limit.put("key", "consumer_name");
        limit.put("rejected_code", 429);
        limit.put("rejected_msg", "共享服务调用超过 QPS 限额");

        Map<String, Object> prometheus = Map.of("prefer_name", true);
        String lua = "return function(conf, ctx) "
                + "ngx.req.set_header('X-Share-Api-Code', '" + api.getApiCode() + "'); "
                + "ngx.req.set_header('X-Share-Gateway', 'apisix'); "
                + "end";
        Map<String, Object> serverless = new LinkedHashMap<>();
        serverless.put("phase", "rewrite");
        serverless.put("functions", List.of(lua));

        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("jwt-auth", jwt);
        plugins.put("limit-req", limit);
        plugins.put("prometheus", prometheus);
        plugins.put("serverless-pre-function", serverless);

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", "share-" + api.getApiCode().toLowerCase(Locale.ROOT));
        route.put("desc", api.getApiName());
        route.put("uri", "/api/open/v1/" + api.getApiCode());
        route.put("methods", List.of(api.getHttpMethod() == null ? "GET" : api.getHttpMethod()));
        route.put("upstream", upstream);
        route.put("plugins", plugins);
        route.put("labels", Map.of("managed-by", "share-exchange", "api-code", api.getApiCode()));

        HttpResponse<String> response = request("PUT", "/apisix/admin/routes/" + routeId, JsonUtil.toJson(route));
        ensureSuccess(response);
    }

    private void deleteRoute(String routeId) throws Exception {
        HttpResponse<String> response = request("DELETE", "/apisix/admin/routes/" + routeId, null);
        if (response.statusCode() != 404) {
            ensureSuccess(response);
        }
    }

    private void ensureConsumer() throws Exception {
        String secret = jwtSecret == null || jwtSecret.isBlank() ? System.getenv("SHARE_JWT_SECRET") : jwtSecret;
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置 APISIX JWT consumer secret");
        }
        Map<String, Object> jwt = new LinkedHashMap<>();
        jwt.put("key", jwtConsumer);
        jwt.put("secret", secret);
        jwt.put("algorithm", "HS256");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", jwtConsumer);
        body.put("plugins", Map.of("jwt-auth", jwt));
        HttpResponse<String> response = request("PUT", "/apisix/admin/consumers", JsonUtil.toJson(body));
        ensureSuccess(response);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path, null);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        String base = adminUrl.endsWith("/") ? adminUrl.substring(0, adminUrl.length() - 1) : adminUrl;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(8))
                .header("X-API-KEY", adminKey)
                .header("Content-Type", "application/json");
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("APISIX Admin " + response.statusCode() + ": " + truncate(response.body(), 300));
        }
    }

    private String routeIdOf(ShareApi api) {
        String raw = api.getApiCode() == null ? "unknown" : api.getApiCode().toLowerCase(Locale.ROOT);
        String id = "share-" + raw.replaceAll("[^a-z0-9-]", "-");
        return id.length() > 64 ? id.substring(0, 64) : id;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public record SyncResult(boolean success, String status, String routeId, String message) {
    }
}
