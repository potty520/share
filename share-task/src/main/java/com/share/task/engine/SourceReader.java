package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.task.entity.TkTask;
import com.share.task.service.MetadataReadService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 源端读取器（FR-02）：支持内置 H2 样例表（JdbcTemplate 真实查询）与 CSV 文件；
 * 若任务登记了 sourceId，会跨域只读 ds_source 取连接信息写入流转日志，便于追溯。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceReader {

    private static final String IDENTIFIER_REGEX = "^[A-Za-z_][A-Za-z0-9_]*$";

    private final JdbcTemplate jdbcTemplate;

    private final EngineSettings settings;

    private final MetadataReadService metadataReadService;

    /** 源端读取结果。 */
    @Getter
    @Setter
    public static class SourceData {
        /** 源类型：H2_TABLE / CSV_FILE */
        private String sourceType;
        /** 源端引用（表名或文件路径） */
        private String sourceRef;
        /** 数据行 */
        private List<Map<String, Object>> rows = new ArrayList<>();
        /** 连接/挂接信息描述（用于日志与追溯） */
        private String connectionInfo;
    }

    /** 读取源端全部数据（受 share.engine.max-rows-per-run 限制）。 */
    public SourceData read(TkTask task) {
        SourceData data = new SourceData();
        String sourceType = resolveSourceType(task);
        data.setSourceType(sourceType);
        String connectionInfo = describeDataSource(task);
        data.setConnectionInfo(connectionInfo);

        if ("CSV_FILE".equals(sourceType)) {
            Path path = resolvePath(task.getSourceFilePath());
            data.setSourceRef(path.toString());
            if (!Files.exists(path)) {
                throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "源文件不存在：" + path);
            }
            try {
                List<Map<String, Object>> rows = CsvUtil.read(path);
                data.setRows(limit(rows));
            } catch (IOException e) {
                throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "读取源 CSV 失败：" + e.getMessage());
            }
            return data;
        }

        String table = task.getSourceTable();
        if (table == null || table.isBlank()) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED, "源表名为空，无法执行库表同步");
        }
        validateIdentifier(table, "源表名");
        data.setSourceRef(table);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from " + table);
            List<Map<String, Object>> ordered = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                ordered.add(new LinkedHashMap<>(row));
            }
            data.setRows(limit(ordered));
        } catch (Exception e) {
            throw new BizException(ErrorCode.TASK_ENGINE_FAILED,
                    "读取源表 " + table + " 失败：" + e.getMessage());
        }
        return data;
    }

    /** 读取指定表（质量检核/预览复用）。 */
    public List<Map<String, Object>> readTable(String table, int maxRows) {
        validateIdentifier(table, "表名");
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from " + table);
            if (maxRows > 0 && rows.size() > maxRows) {
                rows = new ArrayList<>(rows.subList(0, maxRows));
            }
            return rows;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取表 " + table + " 失败：" + e.getMessage());
        }
    }

    /** 源类型：显式配置优先，否则按“有文件路径 → CSV，有表名 → 库表”推断。 */
    public static String resolveSourceType(TkTask task) {
        if (task.getSourceType() != null && !task.getSourceType().isBlank()) {
            return task.getSourceType().trim().toUpperCase();
        }
        if (task.getSourceFilePath() != null && !task.getSourceFilePath().isBlank()) {
            return "CSV_FILE";
        }
        return "H2_TABLE";
    }

    /** 目标类型推断（目标端写入器复用）。 */
    public static String resolveTargetType(TkTask task) {
        if (task.getTargetType() != null && !task.getTargetType().isBlank()) {
            return task.getTargetType().trim().toUpperCase();
        }
        if (task.getTargetFilePath() != null && !task.getTargetFilePath().isBlank()) {
            return "CSV_FILE";
        }
        return "H2_TABLE";
    }

    /** 表名/列名白名单校验，避免 SQL 注入。 */
    public static void validateIdentifier(String name, String label) {
        if (name == null || !name.matches(IDENTIFIER_REGEX)) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + " 不合法：" + name);
        }
    }

    /** 相对路径按应用工作目录解析。 */
    public static Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir", ".")).resolve(filePath).normalize();
        }
        return path;
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> rows) {
        int max = settings.getMaxRowsPerRun();
        if (rows.size() > max) {
            log.warn("源端数据量 {} 超过单次上限 {}，仅处理前 {} 行", rows.size(), max, max);
            return new ArrayList<>(rows.subList(0, max));
        }
        return rows;
    }

    /** 跨域只读 ds_source 取连接信息（不参与实际连接，用于日志留痕与追溯）。 */
    private String describeDataSource(TkTask task) {
        StringBuilder info = new StringBuilder();
        if (task.getSourceId() != null) {
            Optional<Map<String, Object>> source = metadataReadService.findDataSource(task.getSourceId());
            if (source.isEmpty()) {
                info.append("sourceId=").append(task.getSourceId()).append("（ds_source 无记录，使用内置样例源）");
            } else {
                Map<String, Object> map = source.get();
                String name = MetadataReadService.pick(map, "sourceName", "source_name", "name");
                String type = MetadataReadService.pick(map, "sourceType", "source_type", "type");
                String host = MetadataReadService.pick(map, "host");
                String port = MetadataReadService.pick(map, "port");
                String dbName = MetadataReadService.pick(map, "dbName", "db_name");
                String jdbcUrl = MetadataReadService.pick(map, "jdbcUrl", "jdbc_url");
                String owner = MetadataReadService.pick(map, "owner", "deptName", "dept_name");
                info.append("ds_source[id=").append(task.getSourceId())
                        .append(", name=").append(name)
                        .append(", type=").append(type)
                        .append(", host=").append(host)
                        .append(':').append(port)
                        .append(", db=").append(dbName)
                        .append(", jdbcUrl=").append(jdbcUrl == null ? "未配置" : "已配置")
                        .append(", owner=").append(owner)
                        .append("]");
            }
        }
        // 目录资源挂接信息与密级（跨域只读 cat_catalog，缺失时跳过）
        if (task.getCatalogId() != null) {
            Optional<Map<String, Object>> catalog = metadataReadService.findCatalog(task.getCatalogId());
            catalog.ifPresent(map -> {
                String resourceName = MetadataReadService.pick(map, "resourceName", "resource_name");
                String level = MetadataReadService.pick(map, "level");
                String tableName = MetadataReadService.pick(map, "tableName", "table_name");
                if (info.length() > 0) {
                    info.append("; ");
                }
                info.append("cat_catalog[id=").append(task.getCatalogId())
                        .append(", name=").append(resourceName)
                        .append(", attachTable=").append(tableName)
                        .append(", level=").append(level)
                        .append("]");
            });
        }
        return info.length() == 0 ? null : info.toString();
    }
}
