package com.share.common.jdbc;

import java.util.Locale;

/**
 * JDBC 连接器公共映射：集中维护数据库类型、驱动类和连接串模板。
 * <p>仅负责纯字符串转换，不持有连接或凭证，供数据源探测与交换引擎复用。</p>
 */
public final class JdbcConnectorSupport {

    private JdbcConnectorSupport() {
    }

    /** 返回数据库类型对应的 JDBC 驱动类；未知类型返回 null。 */
    public static String driverClass(String type) {
        return switch (normalize(type)) {
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "DM" -> "dm.jdbc.driver.DmDriver";
            case "HIVE" -> "org.apache.hive.jdbc.HiveDriver";
            case "CLICKHOUSE" -> "com.clickhouse.jdbc.ClickHouseDriver";
            default -> null;
        };
    }

    /** 优先使用显式 jdbcUrl，否则按数据库类型生成连接串。 */
    public static String buildUrl(String type, String host, Integer port, String dbName, String jdbcUrl) {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return jdbcUrl.trim();
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalizedHost = host.trim();
        String database = dbName == null ? "" : dbName.trim();
        int defaultPort = defaultPort(type);
        int actualPort = port == null || port <= 0 ? defaultPort : port;
        return switch (normalize(type)) {
            case "MYSQL" -> "jdbc:mysql://" + normalizedHost + ":" + actualPort + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                    + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            case "POSTGRESQL" -> "jdbc:postgresql://" + normalizedHost + ":" + actualPort + "/" + database;
            case "SQLSERVER" -> "jdbc:sqlserver://" + normalizedHost + ":" + actualPort
                    + ";databaseName=" + database + ";encrypt=true;trustServerCertificate=true";
            case "ORACLE" -> "jdbc:oracle:thin:@//" + normalizedHost + ":" + actualPort + "/" + database;
            case "DM" -> "jdbc:dm://" + normalizedHost + ":" + actualPort + "/" + database;
            case "HIVE" -> "jdbc:hive2://" + normalizedHost + ":" + actualPort + "/" + database;
            case "CLICKHOUSE" -> "jdbc:clickhouse://" + normalizedHost + ":" + actualPort + "/" + database;
            default -> null;
        };
    }

    /** 默认端口。 */
    public static int defaultPort(String type) {
        return switch (normalize(type)) {
            case "MYSQL" -> 3306;
            case "POSTGRESQL" -> 5432;
            case "SQLSERVER" -> 1433;
            case "ORACLE" -> 1521;
            case "DM" -> 5236;
            case "HIVE" -> 10000;
            case "CLICKHOUSE" -> 8123;
            default -> 0;
        };
    }

    /** 是否为已纳入 JDBC 交换的数据库类型。 */
    public static boolean isJdbcDatabase(String type) {
        return driverClass(type) != null;
    }

    private static String normalize(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }
}
