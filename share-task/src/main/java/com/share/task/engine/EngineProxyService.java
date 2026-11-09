package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.jdbc.JdbcConnectorSupport;
import com.share.common.util.CryptoUtil;
import com.share.common.util.JsonUtil;
import com.share.task.dto.MappingRule;
import com.share.task.entity.SwBatch;
import com.share.task.entity.TkTask;
import com.share.task.repository.SwBatchRepository;
import com.share.task.service.MetadataReadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 外部执行引擎代理（架构演进：SeaTunnel / Flink CDC）。
 * <p>把平台任务定义翻译成 SeaTunnel HOCON 或 Flink SQL 作业，按批次保留作业配置；
 * 外部引擎启用时可提交作业并通过 Flink REST 回采运行指标，否则由内置引擎执行并保留配置作为交付物。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngineProxyService {

    private final MetadataReadService metadataReadService;
    private final SwBatchRepository batchRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${share.engine.proxy.provider:seatunnel}")
    private String defaultProvider;

    @Value("${share.engine.proxy.config-dir:./data/engine-jobs}")
    private String configDir;

    @Value("${share.engine.proxy.flink-rest-url:}")
    private String flinkRestUrl;

    @Value("${share.engine.proxy.submit-enabled:false}")
    private boolean submitEnabled;

    @Value("${share.crypto.key:share-exchange-local-crypto-key-change-me}")
    private String cryptoKey;

    private volatile SecretKeySpec secretKey;

    public String defaultProvider() {
        return normalizeProvider(defaultProvider);
    }

    /**
     * 为批次生成作业配置并写回 sw_batch。生成失败不阻断内置引擎执行。
     */
    public Map<String, Object> attachJob(TkTask task, SwBatch batch) {
        Map<String, Object> result = new LinkedHashMap<>();
        String provider = defaultProvider();
        try {
            RenderResult rendered = render(task, provider);
            Path file = writeConfig(batch.getBatchId(), provider, rendered.content());
            batch.setEngineProvider(provider);
            batch.setEngineConfigPath(file.toString());
            batch.setEngineStatus(submitEnabled ? "CREATED" : "FALLBACK");
            batch.setEngineSyncTime(LocalDateTime.now());
            if (submitEnabled) {
                SubmitResult submitted = submit(provider, batch.getBatchId(), file, rendered.content());
                batch.setEngineJobId(submitted.jobId());
                batch.setEngineStatus(submitted.accepted() ? "SUBMITTED" : "CREATED");
                result.put("submitAccepted", submitted.accepted());
                result.put("submitMessage", submitted.message());
            } else {
                result.put("submitAccepted", false);
                result.put("submitMessage", "作业提交开关关闭，配置已生成并由内置引擎执行");
            }
            batchRepository.save(batch);
            result.put("provider", provider);
            result.put("configPath", file.toString());
            result.put("warnings", rendered.warnings());
            result.put("config", rendered.content());
            log.info("引擎代理已生成作业配置 batch={} provider={} path={}", batch.getBatchId(), provider, file);
        } catch (Exception e) {
            batch.setEngineProvider(provider);
            batch.setEngineStatus("FALLBACK");
            batch.setEngineSyncTime(LocalDateTime.now());
            batchRepository.save(batch);
            result.put("provider", provider);
            result.put("error", e.getMessage());
            result.put("submitAccepted", false);
            log.warn("引擎代理生成作业配置失败 batch={}: {}", batch.getBatchId(), e.getMessage());
        }
        return result;
    }

    /** 生成指定 provider 的作业配置（不落盘）。 */
    public RenderResult render(TkTask task, String provider) {
        String normalized = normalizeProvider(provider);
        List<String> warnings = new ArrayList<>();
        String content = switch (normalized) {
            case "flink", "flink_cdc" -> renderFlink(task, warnings);
            default -> renderSeaTunnel(task, warnings);
        };
        return new RenderResult(normalized, content, List.copyOf(warnings));
    }

    /** 预览任务作业配置。 */
    public Map<String, Object> preview(TkTask task, String provider) {
        RenderResult rendered = render(task, provider == null || provider.isBlank() ? defaultProvider() : provider);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("taskCode", task.getTaskCode());
        result.put("provider", rendered.provider());
        result.put("config", rendered.content());
        result.put("warnings", rendered.warnings());
        result.put("submitEnabled", submitEnabled);
        return result;
    }

    /** 回采一个批次的引擎指标；外部作业不可用时返回本地批次指标。 */
    public Map<String, Object> collectMetrics(SwBatch batch) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("batchId", batch.getBatchId());
        metrics.put("provider", batch.getEngineProvider());
        metrics.put("jobId", batch.getEngineJobId());
        metrics.put("engineStatus", batch.getEngineStatus());
        metrics.put("batchStatus", batch.getStatus());
        metrics.put("readRows", batch.getReadRows());
        metrics.put("successRows", batch.getSuccessRows());
        metrics.put("failedRows", batch.getFailedRows());
        metrics.put("costMs", batch.getCostMs());
        if (batch.getEngineJobId() != null && !batch.getEngineJobId().isBlank()
                && flinkRestUrl != null && !flinkRestUrl.isBlank()) {
            try {
                Map<String, Object> remote = fetchFlinkMetrics(batch.getEngineJobId());
                metrics.put("remote", remote);
                metrics.put("engineStatus", remote.getOrDefault("state", batch.getEngineStatus()));
            } catch (Exception e) {
                metrics.put("remoteError", e.getMessage());
            }
        }
        String json = JsonUtil.toJson(metrics);
        batch.setEngineMetricsJson(json);
        batch.setEngineSyncTime(LocalDateTime.now());
        batchRepository.save(batch);
        metrics.put("syncTime", batch.getEngineSyncTime());
        return metrics;
    }

    private Map<String, Object> fetchFlinkMetrics(String jobId) throws Exception {
        String base = flinkRestUrl.endsWith("/") ? flinkRestUrl.substring(0, flinkRestUrl.length() - 1) : flinkRestUrl;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", getJson(base + "/jobs/" + jobId));
        try {
            result.put("metrics", getJson(base + "/jobs/" + jobId + "/metrics?get=numRecordsIn,numRecordsOut,numBytesIn,numBytesOut"));
            result.put("checkpoints", getJson(base + "/jobs/" + jobId + "/checkpoints"));
        } catch (Exception e) {
            result.put("metricsError", e.getMessage());
        }
        Object job = result.get("job");
        if (job instanceof Map<?, ?> map && map.get("state") != null) {
            result.put("state", map.get("state"));
        }
        return result;
    }

    private Map<String, Object> getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Flink REST " + response.statusCode());
        }
        return JsonUtil.fromJson(response.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    private SubmitResult submit(String provider, String batchId, Path configFile, String content) {
        if (!"flink".equals(provider) && !"flink_cdc".equals(provider)) {
            return new SubmitResult(false, null, "SeaTunnel 提交适配已保留，当前以内置引擎执行；可通过 seaTunnel REST 接入");
        }
        if (flinkRestUrl == null || flinkRestUrl.isBlank()) {
            return new SubmitResult(false, null, "未配置 share.engine.proxy.flink-rest-url");
        }
        return new SubmitResult(false, null, "Flink SQL 作业请通过 SQL Gateway 提交；配置已生成: " + configFile.getFileName());
    }

    private String renderSeaTunnel(TkTask task, List<String> warnings) {
        DbSpec source = dbSpec(task.getSourceId(), "源端", warnings);
        DbSpec target = dbSpec(task.getTargetId(), "目标端", warnings);
        List<MappingRule> mappings = mappings(task);
        String selectColumns = sourceFields(mappings, task.getSourceTable());
        String sourceQuery = "select " + selectColumns + " from " + safeTable(task.getSourceTable());
        if (task.getFilterCondition() != null && !task.getFilterCondition().isBlank()) {
            sourceQuery += " where " + task.getFilterCondition();
        }
        if ("INCREMENT".equalsIgnoreCase(task.getTaskType()) && task.getSourceIncrementColumn() != null) {
            String watermark = task.getLastWatermark() == null ? task.getSourceIncrementStart() : task.getLastWatermark();
            if (watermark != null && !watermark.isBlank()) {
                sourceQuery += (sourceQuery.contains(" where ") ? " and " : " where ")
                        + safeIdentifier(task.getSourceIncrementColumn()) + " > '" + escape(watermark) + "'";
            }
            sourceQuery += " order by " + safeIdentifier(task.getSourceIncrementColumn()) + " asc";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("env {\n");
        sb.append("  job.mode = \"").append("INCREMENT".equalsIgnoreCase(task.getTaskType()) ? "STREAMING" : "BATCH").append("\"\n");
        sb.append("  parallelism = 1\n");
        sb.append("  job.id = \"share-").append(task.getTaskCode()).append("-${batchId}\"\n");
        sb.append("}\n\nsource {\n  Jdbc {\n");
        appendSeaTunnelDb(sb, source);
        sb.append("    query = \"").append(escape(sourceQuery)).append("\"\n");
        sb.append("    partition_column = \"")
                .append(task.getSourceIncrementColumn() == null ? "" : safeIdentifier(task.getSourceIncrementColumn()))
                .append("\"\n  }\n}\n\n");
        sb.append("transform {\n  # 字段映射/算子由平台定义，外部作业使用 SeaTunnel SQL 转换模板\n");
        sb.append("  Sql {\n    query = \"select ").append(targetFields(mappings, task.getTargetTable()))
                .append(" from Jdbc\"\n  }\n}\n\n");
        sb.append("sink {\n  Jdbc {\n");
        appendSeaTunnelDb(sb, target);
        sb.append("    database = \"").append(escape(target.database())).append("\"\n");
        sb.append("    table = \"").append(escape(safeTable(task.getTargetTable()))).append("\"\n");
        sb.append("    save_mode = \"overwrite\"\n");
        sb.append("  }\n}\n");
        return sb.toString();
    }

    private String renderFlink(TkTask task, List<String> warnings) {
        DbSpec source = dbSpec(task.getSourceId(), "源端", warnings);
        DbSpec target = dbSpec(task.getTargetId(), "目标端", warnings);
        List<MappingRule> mappings = mappings(task);
        String srcTable = safeTable(task.getSourceTable());
        String dstTable = safeTable(task.getTargetTable());
        StringBuilder sb = new StringBuilder();
        sb.append("-- Share Exchange generated Flink SQL (batch ").append("${batchId}").append(")\n");
        sb.append("SET 'execution.runtime-mode' = '")
                .append("INCREMENT".equalsIgnoreCase(task.getTaskType()) ? "streaming" : "batch").append("';\n");
        sb.append("SET 'pipeline.name' = 'share-").append(task.getTaskCode()).append("';\n\n");
        sb.append("CREATE TABLE share_source (\n");
        for (int i = 0; i < mappings.size(); i++) {
            MappingRule rule = mappings.get(i);
            String column = rule.getSourceField() == null || rule.getSourceField().isBlank()
                    ? rule.getTargetField() : rule.getSourceField();
            sb.append("  ").append(quoteIdentifier(column)).append(" STRING");
            if (i < mappings.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(") WITH (\n  'connector' = '").append(source.isMysql() ? "mysql-cdc" : "jdbc").append("',\n")
                .append("  'url' = '").append(escape(source.url())).append("',\n")
                .append("  'username' = '").append(escape(source.username())).append("',\n")
                .append("  'password' = '").append(escape(source.password())).append("',\n")
                .append("  'table-name' = '").append(escape(srcTable)).append("'\n);\n\n");
        sb.append("CREATE TABLE share_sink (\n");
        for (int i = 0; i < mappings.size(); i++) {
            MappingRule rule = mappings.get(i);
            String column = rule.getTargetField() == null || rule.getTargetField().isBlank()
                    ? rule.getSourceField() : rule.getTargetField();
            sb.append("  ").append(quoteIdentifier(column)).append(" STRING");
            if (i < mappings.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(") WITH (\n  'connector' = 'jdbc',\n")
                .append("  'url' = '").append(escape(target.url())).append("',\n")
                .append("  'username' = '").append(escape(target.username())).append("',\n")
                .append("  'password' = '").append(escape(target.password())).append("',\n")
                .append("  'table-name' = '").append(escape(dstTable)).append("'\n);\n\n");
        sb.append("INSERT INTO share_sink SELECT ");
        for (int i = 0; i < mappings.size(); i++) {
            MappingRule rule = mappings.get(i);
            String column = rule.getSourceField() == null || rule.getSourceField().isBlank()
                    ? rule.getTargetField() : rule.getSourceField();
            sb.append(quoteIdentifier(column));
            if (i < mappings.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(" FROM share_source");
        if (task.getFilterCondition() != null && !task.getFilterCondition().isBlank()) {
            sb.append(" WHERE ").append(task.getFilterCondition());
        }
        sb.append(";\n");
        if (mappings.isEmpty()) {
            warnings.add("任务未配置字段映射，Flink SQL 只有占位列，提交前请补全映射");
        }
        warnings.add("Flink SQL 列类型统一生成为 STRING，生产提交前应按源表元数据补全类型");
        return sb.toString();
    }

    private void appendSeaTunnelDb(StringBuilder sb, DbSpec db) {
        sb.append("    url = \"").append(escape(db.url())).append("\"\n");
        sb.append("    driver = \"").append(escape(db.driver())).append("\"\n");
        sb.append("    user = \"").append(escape(db.username())).append("\"\n");
        sb.append("    password = \"").append(escape(db.password())).append("\"\n");
    }

    private DbSpec dbSpec(Long dataSourceId, String role, List<String> warnings) {
        if (dataSourceId == null) {
            warnings.add(role + "未绑定外部数据源，生成配置使用占位连接串");
            return new DbSpec("jdbc:mysql://127.0.0.1:3306/share_exchange", "com.mysql.cj.jdbc.Driver",
                    "share", "change-me", "share_exchange", "MYSQL");
        }
        Map<String, Object> source = metadataReadService.findDataSource(dataSourceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, role + "数据源不存在：" + dataSourceId));
        String type = MetadataReadService.pick(source, "sourceType", "source_type", "type");
        String url = JdbcConnectorSupport.buildUrl(type,
                MetadataReadService.pick(source, "host"),
                toInteger(MetadataReadService.pick(source, "port")),
                MetadataReadService.pick(source, "dbName", "db_name"),
                MetadataReadService.pick(source, "jdbcUrl", "jdbc_url"));
        if (url == null || url.isBlank()) {
            warnings.add(role + "缺少 JDBC 连接串，生成配置使用占位连接串");
            url = "jdbc:mysql://127.0.0.1:3306/share_exchange";
        }
        String driver = JdbcConnectorSupport.driverClass(type);
        if (driver == null) {
            driver = "com.mysql.cj.jdbc.Driver";
        }
        String username = Optional.ofNullable(MetadataReadService.pick(source, "username", "user")).orElse("share");
        String password = decrypt(MetadataReadService.pick(source, "passwordCipher", "password_cipher"));
        String database = Optional.ofNullable(MetadataReadService.pick(source, "dbName", "db_name")).orElse("");
        return new DbSpec(url, driver, username, password == null ? "" : password, database, type);
    }

    private List<MappingRule> mappings(TkTask task) {
        try {
            List<MappingRule> rules = JsonUtil.fromJsonList(task.getMappingJson(), MappingRule.class);
            if (rules != null && !rules.isEmpty()) {
                return rules;
            }
        } catch (Exception e) {
            log.debug("任务 {} 映射解析失败: {}", task.getTaskCode(), e.getMessage());
        }
        MappingRule fallback = new MappingRule();
        fallback.setSourceField("*");
        fallback.setTargetField("*");
        return List.of(fallback);
    }

    private String sourceFields(List<MappingRule> mappings, String fallback) {
        List<String> fields = new ArrayList<>();
        for (MappingRule rule : mappings) {
            String field = rule.getSourceField();
            if (field != null && !field.isBlank() && !"*".equals(field.trim())) {
                fields.add(quoteIdentifier(field));
            }
        }
        return fields.isEmpty() ? "*" : String.join(", ", fields);
    }

    private String targetFields(List<MappingRule> mappings, String fallback) {
        List<String> fields = new ArrayList<>();
        for (MappingRule rule : mappings) {
            String field = rule.getTargetField();
            if (field != null && !field.isBlank() && !"*".equals(field.trim())) {
                fields.add(quoteIdentifier(field));
            }
        }
        return fields.isEmpty() ? "*" : String.join(", ", fields);
    }

    private String quoteIdentifier(String value) {
        return "`" + value.replace("`", "") + "`";
    }

    private String safeIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法字段名：" + value);
        }
        return value;
    }

    private String safeTable(String value) {
        if (value == null || value.isBlank()) {
            return "unknown_table";
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法表名：" + value);
        }
        return trimmed;
    }

    private String decrypt(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        if (secretKey == null) {
            secretKey = CryptoUtil.deriveKey(cryptoKey);
        }
        try {
            return CryptoUtil.decrypt(cipher, secretKey);
        } catch (Exception e) {
            log.warn("引擎代理解密数据源口令失败: {}", e.getMessage());
            return "";
        }
    }

    private Path writeConfig(String batchId, String provider, String content) throws Exception {
        Path dir = Paths.get(configDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String suffix = provider.startsWith("flink") ? ".sql" : ".conf";
        Path file = dir.resolve(batchId + "-" + provider + suffix);
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignore) {
            // Windows 本地开发环境不支持 POSIX 权限
        }
        return file;
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "seatunnel";
        }
        String value = provider.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "flink_cdc", "flink-sql", "flink" -> "flink";
            default -> "seatunnel";
        };
    }

    private Integer toInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record RenderResult(String provider, String content, List<String> warnings) {
    }

    private record SubmitResult(boolean accepted, String jobId, String message) {
    }

    private record DbSpec(String url, String driver, String username, String password, String database, String type) {
        boolean isMysql() {
            return type != null && type.toUpperCase(Locale.ROOT).contains("MYSQL");
        }
    }
}
