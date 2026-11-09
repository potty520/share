package com.share.task.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 CSV 读写（UTF-8，支持双引号转义）。交换引擎的 CSV 源端/目标端共用，
 * 不引入第三方依赖，保证文件通道真实可跑。
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    /** 读取 CSV：首行为表头，返回保持列顺序的行列表。 */
    public static List<Map<String, Object>> read(Path path) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.exists(path)) {
            throw new IOException("文件不存在: " + path);
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            if (!headerLine.isEmpty() && headerLine.charAt(0) == '\uFEFF') {
                headerLine = headerLine.substring(1);
            }
            List<String> headers = parseLine(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                List<String> values = parseLine(line);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < values.size() ? values.get(i) : null);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** 写入 CSV：覆盖写目标文件（等价于“删除旧批次 + 重新落盘”，保证幂等）。 */
    public static void write(Path path, List<String> columns, List<? extends Map<String, Object>> rows) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", columns.stream().map(CsvUtil::escape).toList()));
            writer.newLine();
            for (Map<String, Object> row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        line.append(',');
                    }
                    Object value = row.get(columns.get(i));
                    line.append(escape(value == null ? "" : String.valueOf(value)));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }
    }

    /** 解析一行 CSV（支持引号包裹与转义双引号）。 */
    public static List<String> parseLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
