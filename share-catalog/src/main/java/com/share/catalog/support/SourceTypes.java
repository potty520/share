package com.share.catalog.support;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源连接器类型字典（FR-01）。
 * <p>集中维护类型编码/名称/接入类别/默认端口/驱动信息，连接器类型表与前端下拉都以此为准。</p>
 */
public final class SourceTypes {

    private SourceTypes() {
    }

    /** 单个连接器类型定义。 */
    @Getter
    @AllArgsConstructor
    public static class SourceTypeDef {
        private final String code;
        private final String name;
        /** DB / FILE / MQ / API */
        private final String category;
        private final Integer defaultPort;
        private final String driverClass;
        private final String urlTemplate;
        private final boolean requireHost;
        private final boolean requirePath;
        private final String description;
    }

    private static final List<SourceTypeDef> ALL = List.of(
            new SourceTypeDef("MYSQL", "MySQL", "DB", 3306, "com.mysql.cj.jdbc.Driver",
                    "jdbc:mysql://{host}:{port}/{dbName}?useUnicode=true&characterEncoding=utf8",
                    true, false, "关系型数据库，财务部业务库常用"),
            new SourceTypeDef("ORACLE", "Oracle", "DB", 1521, "oracle.jdbc.OracleDriver",
                    "jdbc:oracle:thin:@//{host}:{port}/{dbName}", true, false, "关系型数据库"),
            new SourceTypeDef("POSTGRESQL", "PostgreSQL", "DB", 5432, "org.postgresql.Driver",
                    "jdbc:postgresql://{host}:{port}/{dbName}", true, false, "关系型/分析型数据库"),
            new SourceTypeDef("SQLSERVER", "SQL Server", "DB", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                    "jdbc:sqlserver://{host}:{port};databaseName={dbName}", true, false, "关系型数据库"),
            new SourceTypeDef("DM", "达梦 DM", "DB", 5236, "dm.jdbc.driver.DmDriver",
                    "jdbc:dm://{host}:{port}/{dbName}", true, false, "国产化关系型数据库"),
            new SourceTypeDef("HIVE", "Hive", "DB", 10000, "org.apache.hive.jdbc.HiveDriver",
                    "jdbc:hive2://{host}:{port}/{dbName}", true, false, "数据仓库"),
            new SourceTypeDef("CLICKHOUSE", "ClickHouse", "DB", 8123, "com.clickhouse.jdbc.ClickHouseDriver",
                    "jdbc:clickhouse://{host}:{port}/{dbName}", true, false, "列式分析数据库"),
            new SourceTypeDef("FTP", "FTP 文件服务", "FILE", 21, null, "ftp://{host}:{port}{filePath}",
                    true, true, "文件类数据源，按目录收发"),
            new SourceTypeDef("SFTP", "SFTP 文件服务", "FILE", 22, null, "sftp://{host}:{port}{filePath}",
                    true, true, "加密文件传输，需主机与目录"),
            new SourceTypeDef("OSS", "对象存储 OSS", "FILE", 443, null, "https://{host}/{dbName}",
                    true, true, "对象存储桶，按前缀拉取"),
            new SourceTypeDef("KAFKA", "Kafka 消息队列", "MQ", 9092, null, "kafka://{host}:{port}/{dbName}",
                    true, false, "消息类数据源，dbName 存 Topic"),
            new SourceTypeDef("HTTP", "HTTP 接口", "API", 80, null, "{apiUrl}",
                    false, false, "REST 接口，按地址拉取"),
            new SourceTypeDef("WEBSERVICE", "WebService 接口", "API", 80, null, "{apiUrl}?wsdl",
                    false, false, "SOAP 接口，按 WSDL 地址拉取")
    );

    private static final Map<String, SourceTypeDef> INDEX = new LinkedHashMap<>();

    static {
        for (SourceTypeDef def : ALL) {
            INDEX.put(def.getCode(), def);
        }
    }

    public static List<SourceTypeDef> all() {
        return ALL;
    }

    public static SourceTypeDef of(String code) {
        if (code == null) {
            return INDEX.get("MYSQL");
        }
        SourceTypeDef def = INDEX.get(code.trim().toUpperCase());
        return def != null ? def : INDEX.get("MYSQL");
    }

    public static boolean contains(String code) {
        return code != null && INDEX.containsKey(code.trim().toUpperCase());
    }

    /** 类型名称，未知类型原样返回。 */
    public static String nameOf(String code) {
        SourceTypeDef def = code == null ? null : INDEX.get(code.trim().toUpperCase());
        return def == null ? code : def.getName();
    }

    public static String categoryOf(String code) {
        SourceTypeDef def = code == null ? null : INDEX.get(code.trim().toUpperCase());
        return def == null ? "DB" : def.getCategory();
    }

    public static boolean isDatabase(String code) {
        return "DB".equals(categoryOf(code));
    }

    public static boolean isFile(String code) {
        return "FILE".equals(categoryOf(code));
    }

    public static boolean isMessage(String code) {
        return "MQ".equals(categoryOf(code));
    }

    public static boolean isApi(String code) {
        return "API".equals(categoryOf(code));
    }
}
