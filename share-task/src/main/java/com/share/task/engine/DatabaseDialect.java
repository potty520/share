package com.share.task.engine;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;

/**
 * 数据库方言适配：质量规则 SQL 与外部目标表 DDL 不再假设 H2。
 */
public enum DatabaseDialect {

    MYSQL,
    POSTGRESQL,
    SQLSERVER,
    ORACLE,
    H2,
    CLICKHOUSE,
    OTHER;

    public static DatabaseDialect detect(JdbcTemplate jdbcTemplate) {
        try {
            String product = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName());
            return fromProduct(product);
        } catch (Exception e) {
            return OTHER;
        }
    }

    public static DatabaseDialect fromProduct(String product) {
        String value = product == null ? "" : product.trim().toLowerCase(Locale.ROOT);
        if (value.contains("mysql") || value.contains("mariadb")) {
            return MYSQL;
        }
        if (value.contains("postgres")) {
            return POSTGRESQL;
        }
        if (value.contains("microsoft") || value.contains("sql server")) {
            return SQLSERVER;
        }
        if (value.contains("oracle")) {
            return ORACLE;
        }
        if (value.contains("h2")) {
            return H2;
        }
        if (value.contains("clickhouse")) {
            return CLICKHOUSE;
        }
        return OTHER;
    }

    /** 把任意表达式转换为字符串，供正则/空值判断使用。 */
    public String castText(String expression) {
        return switch (this) {
            case MYSQL -> "cast(" + expression + " as char)";
            case SQLSERVER -> "cast(" + expression + " as nvarchar(4000))";
            case ORACLE -> "cast(" + expression + " as varchar2(4000))";
            case POSTGRESQL, CLICKHOUSE -> "cast(" + expression + " as varchar)";
            default -> "cast(" + expression + " as varchar(4000))";
        };
    }

    /** 规范性规则：返回“不匹配正则”的布尔条件。 */
    public String regexMismatch(String field, String escapedRegex) {
        String text = castText(field);
        return switch (this) {
            case POSTGRESQL -> "not (" + text + " ~ '" + escapedRegex + "')";
            case SQLSERVER -> "patindex('" + escapedRegex + "', " + text + ") = 0";
            case CLICKHOUSE -> "not match(" + text + ", '" + escapedRegex + "')";
            case ORACLE -> "not regexp_like(" + text + ", '" + escapedRegex + "')";
            default -> "not regexp_like(" + text + ", '" + escapedRegex + "')";
        };
    }

    /** 及时性规则：字段时间超过 allowedHours 小时的条件。 */
    public String overdueHours(String field, long allowedHours) {
        return switch (this) {
            case MYSQL -> "timestampdiff(HOUR, cast(" + field + " as datetime), current_timestamp) > " + allowedHours;
            case POSTGRESQL -> "extract(epoch from (current_timestamp - cast(" + field
                    + " as timestamp))) / 3600 > " + allowedHours;
            case SQLSERVER -> "datediff(hour, cast(" + field + " as datetime2), current_timestamp) > " + allowedHours;
            case ORACLE -> "(cast(current_timestamp as date) - cast(" + field + " as date)) * 24 > " + allowedHours;
            case CLICKHOUSE -> "dateDiff('hour', toDateTime(" + field + "), now()) > " + allowedHours;
            default -> "datediff('HOUR', cast(" + field + " as timestamp), current_timestamp) > " + allowedHours;
        };
    }

    /** 自动建表时的字符串类型。 */
    public String varcharType(int length) {
        return switch (this) {
            case SQLSERVER -> "nvarchar(" + length + ")";
            case ORACLE -> "varchar2(" + length + ")";
            default -> "varchar(" + length + ")";
        };
    }

    /** 自动建表时的大文本类型。 */
    public String textType() {
        return switch (this) {
            case SQLSERVER -> "nvarchar(max)";
            case ORACLE -> "clob";
            case POSTGRESQL, CLICKHOUSE -> "text";
            default -> "text";
        };
    }

    /** 自动建表时的整数类型。 */
    public String bigintType() {
        return switch (this) {
            case ORACLE -> "number(19)";
            default -> "bigint";
        };
    }

    /** 自动建表时的小数类型。 */
    public String decimalType() {
        return switch (this) {
            case ORACLE -> "number(20,4)";
            default -> "decimal(20,4)";
        };
    }

    /** 自动建表时的日期时间类型。 */
    public String dateTimeType() {
        return switch (this) {
            case MYSQL -> "datetime";
            case SQLSERVER -> "datetime2";
            case ORACLE -> "timestamp";
            case POSTGRESQL, CLICKHOUSE, H2 -> "timestamp";
            default -> "timestamp";
        };
    }

    /** 自动建表时的日期类型。 */
    public String dateType() {
        return switch (this) {
            case MYSQL -> "date";
            case SQLSERVER -> "date";
            case ORACLE -> "date";
            default -> "date";
        };
    }
}
