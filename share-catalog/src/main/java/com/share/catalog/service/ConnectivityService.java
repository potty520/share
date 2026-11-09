package com.share.catalog.service;

import com.share.catalog.dto.SourceTestResult;
import com.share.catalog.entity.DataSource;
import com.share.catalog.support.SourceTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 数据源连通性测试（FR-01）。
 * <p>按接入类别执行真实探测，不做假成功：</p>
 * <ul>
 *   <li>数据库 / 消息类：对 host:port 做 TCP 探测（超时 2s）</li>
 *   <li>文件类：配置了本地路径则校验路径是否存在，配置了主机则做 TCP 探测</li>
 *   <li>接口类：校验 URL 格式（scheme/host），失败直接给出原因</li>
 * </ul>
 */
@Slf4j
@Service
public class ConnectivityService {

    /** 连接探测超时（毫秒） */
    private static final int TIMEOUT_MS = 2000;

    public SourceTestResult test(DataSource source) {
        SourceTypes.SourceTypeDef def = SourceTypes.of(source.getSourceType());
        try {
            if ("API".equals(def.getCategory())) {
                return checkApiUrl(source, def);
            }
            if ("FILE".equals(def.getCategory()) && isLocalPath(source)) {
                return checkLocalPath(source, def);
            }
            return socketProbe(source, def);
        } catch (Exception e) {
            SourceTestResult result = SourceTestResult.fail("UNKNOWN", targetOf(source), 0L,
                    "探测异常：" + summarize(e));
            result.setTypeCode(def.getCode());
            result.setTypeName(def.getName());
            return result;
        }
    }

    /** 文件类：无主机但配置了路径时按本地路径校验。 */
    private boolean isLocalPath(DataSource source) {
        return isBlank(source.getHost()) && !isBlank(source.getFilePath());
    }

    private SourceTestResult socketProbe(DataSource source, SourceTypes.SourceTypeDef def) {
        String target = targetOf(source);
        long start = System.currentTimeMillis();
        if (isBlank(source.getHost()) || source.getPort() == null) {
            return fill(SourceTestResult.fail("TCP", target, 0L,
                    "缺少主机地址或端口，无法探测（" + def.getName() + "）"), def);
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(source.getHost().trim(), source.getPort()), TIMEOUT_MS);
            long latency = System.currentTimeMillis() - start;
            return fill(SourceTestResult.ok("TCP", target, latency,
                    "TCP 连通成功：" + target + " 可达（" + def.getName() + "）"), def);
        } catch (IOException e) {
            long latency = System.currentTimeMillis() - start;
            return fill(SourceTestResult.fail("TCP", target, latency,
                    "TCP 连接失败：" + target + " 不可达，耗时 " + latency + "ms（" + summarize(e) + "）"), def);
        }
    }

    private SourceTestResult checkLocalPath(DataSource source, SourceTypes.SourceTypeDef def) {
        Path path = Paths.get(source.getFilePath().trim());
        long start = System.currentTimeMillis();
        boolean exists = Files.exists(path);
        long latency = System.currentTimeMillis() - start;
        if (!exists) {
            return fill(SourceTestResult.fail("LOCAL_PATH", path.toAbsolutePath().toString(), latency,
                    "本地路径不存在：" + path.toAbsolutePath()), def);
        }
        String kind = Files.isDirectory(path) ? "目录" : "文件";
        String detail = "";
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                detail = "，包含 " + children.limit(1000).count() + " 个条目";
            } catch (IOException ignore) {
                detail = "";
            }
        }
        return fill(SourceTestResult.ok("LOCAL_PATH", path.toAbsolutePath().toString(), latency,
                "本地" + kind + "存在：" + path.toAbsolutePath() + detail), def);
    }

    private SourceTestResult checkApiUrl(DataSource source, SourceTypes.SourceTypeDef def) {
        String url = firstNonBlank(source.getApiUrl(), source.getJdbcUrl());
        long start = System.currentTimeMillis();
        if (isBlank(url)) {
            return fill(SourceTestResult.fail("URL_FORMAT", null, 0L,
                    "缺少接口地址（apiUrl），无法校验"), def);
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return fill(SourceTestResult.fail("URL_FORMAT", url, System.currentTimeMillis() - start,
                        "接口地址不合法：仅支持 http/https，当前为 " + (scheme.isEmpty() ? "空" : scheme)), def);
            }
            if (isBlank(uri.getHost())) {
                return fill(SourceTestResult.fail("URL_FORMAT", url, System.currentTimeMillis() - start,
                        "接口地址不合法：缺少主机名"), def);
            }
            long latency = System.currentTimeMillis() - start;
            return fill(SourceTestResult.ok("URL_FORMAT", url, latency,
                    "接口地址格式校验通过：" + url + "（" + scheme.toUpperCase(Locale.ROOT) + "，调用阶段再做鉴权与响应校验）"), def);
        } catch (Exception e) {
            return fill(SourceTestResult.fail("URL_FORMAT", url, System.currentTimeMillis() - start,
                    "接口地址解析失败：" + summarize(e)), def);
        }
    }

    private SourceTestResult fill(SourceTestResult result, SourceTypes.SourceTypeDef def) {
        result.setTypeCode(def.getCode());
        result.setTypeName(def.getName());
        return result;
    }

    private String targetOf(DataSource source) {
        if (isBlank(source.getHost())) {
            return source.getFilePath() != null ? source.getFilePath()
                    : firstNonBlank(source.getApiUrl(), null);
        }
        return source.getPort() == null ? source.getHost() : source.getHost() + ":" + source.getPort();
    }

    private String summarize(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }
}
