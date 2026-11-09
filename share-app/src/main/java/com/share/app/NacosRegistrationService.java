package com.share.app;

import com.share.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Nacos 服务注册（FR-13 架构演进）。
 * <p>不引入 Spring Cloud 依赖，直接通过 Nacos OpenAPI 完成临时实例注册、心跳与下线，
 * 便于在单体与拆分进程间复用；Nacos 不可用时只告警、不阻塞主服务启动。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "share.registry.nacos.enabled", havingValue = "true")
public class NacosRegistrationService {

    @Value("${share.registry.nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${share.registry.nacos.service-name:share-exchange}")
    private String serviceName;

    @Value("${share.registry.nacos.group:DEFAULT_GROUP}")
    private String groupName;

    @Value("${share.registry.nacos.namespace:}")
    private String namespaceId;

    @Value("${share.registry.nacos.username:}")
    private String username;

    @Value("${share.registry.nacos.password:}")
    private String password;

    @Value("${share.registry.nacos.ip:}")
    private String configuredIp;

    @Value("${share.registry.nacos.cluster:DEFAULT}")
    private String clusterName;

    @Value("${share.registry.nacos.role:center}")
    private String role;

    @Value("${share.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${server.port:8080}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nacos-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final String startedAt = LocalDateTime.now().toString();
    private volatile boolean registered;
    private volatile String accessToken;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("Nacos 注册已启用 service={} addr={} role={}", serviceName, serverAddr, role);
        executor.scheduleWithFixedDelay(this::tick, 0, 5, TimeUnit.SECONDS);
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        executor.shutdownNow();
        if (registered) {
            try {
                send("DELETE", "/nacos/v1/ns/instance", baseParams());
                log.info("Nacos 实例已下线 service={} {}:{}", serviceName, ip(), port);
            } catch (Exception e) {
                log.warn("Nacos 下线失败：{}", e.getMessage());
            }
        }
    }

    private void tick() {
        try {
            if (!registered) {
                register();
            } else {
                beat();
            }
        } catch (Exception e) {
            registered = false;
            log.warn("Nacos 注册/心跳失败 service={}：{}", serviceName, e.getMessage());
        }
    }

    private void register() throws Exception {
        Map<String, String> params = baseParams();
        params.put("weight", "1");
        params.put("enabled", "true");
        params.put("healthy", "true");
        params.put("ephemeral", "true");
        params.put("metadata", JsonUtil.toJson(metadata()));
        HttpResponse<String> response = send("POST", "/nacos/v1/ns/instance", params);
        if (response.statusCode() == 200 && !"false".equalsIgnoreCase(response.body().trim())) {
            registered = true;
            log.info("Nacos 注册成功 service={} {}:{} group={}", serviceName, ip(), port, groupName);
        } else {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private void beat() throws Exception {
        Map<String, Object> beat = new LinkedHashMap<>();
        beat.put("serviceName", serviceName);
        beat.put("ip", ip());
        beat.put("port", String.valueOf(port));
        beat.put("weight", "1");
        beat.put("cluster", clusterName);
        beat.put("scheduled", true);
        beat.put("metadata", metadata());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serviceName", serviceName);
        params.put("groupName", groupName);
        params.put("beat", JsonUtil.toJson(beat));
        if (!namespaceId.isBlank()) {
            params.put("namespaceId", namespaceId);
        }
        HttpResponse<String> response = send("PUT", "/nacos/v1/ns/instance/beat", params);
        if (response.statusCode() == 200 && response.body().contains("\"clientBeatInterval\"")) {
            return;
        }
        if (response.statusCode() == 403 || response.statusCode() == 401) {
            accessToken = null;
        }
        registered = false;
        throw new IllegalStateException("心跳失败 HTTP " + response.statusCode() + " " + response.body());
    }

    private Map<String, String> baseParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serviceName", serviceName);
        params.put("ip", ip());
        params.put("port", String.valueOf(port));
        params.put("groupName", groupName);
        params.put("clusterName", clusterName);
        if (!namespaceId.isBlank()) {
            params.put("namespaceId", namespaceId);
        }
        return params;
    }

    private Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("role", role);
        metadata.put("version", "1.0.0");
        metadata.put("startedAt", startedAt);
        metadata.put("pid", ProcessHandle.current().pid());
        metadata.put("schedulerEnabled", schedulerEnabled);
        return metadata;
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> params) throws Exception {
        String url = buildUrl(path, params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.DELETE();
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && username != null && !username.isBlank()) {
            login();
            response = http.send(HttpRequest.newBuilder(URI.create(buildUrl(path, params)))
                    .timeout(Duration.ofSeconds(5))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    private void login() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("username", username);
        params.put("password", password);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(buildUrl("/nacos/v1/auth/login", params)))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = JsonUtil.fromJson(response.body(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        if (body != null && body.get("accessToken") != null) {
            accessToken = String.valueOf(body.get("accessToken"));
            log.info("Nacos 认证成功 username={}", username);
        } else {
            throw new IllegalStateException("Nacos 认证失败 HTTP " + response.statusCode());
        }
    }

    private String buildUrl(String path, Map<String, String> params) {
        String addr = serverAddr.startsWith("http") ? serverAddr : "http://" + serverAddr;
        List<String> query = new ArrayList<>();
        params.forEach((k, v) -> {
            if (v != null) {
                query.add(encode(k) + "=" + encode(v));
            }
        });
        if (accessToken != null && !accessToken.isBlank()) {
            query.add("accessToken=" + encode(accessToken));
        }
        return addr + path + "?" + String.join("&", query);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String ip() {
        if (configuredIp != null && !configuredIp.isBlank()) {
            return configuredIp.trim();
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
