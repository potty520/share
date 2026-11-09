package com.share.task.engine;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.jdbc.JdbcConnectorSupport;
import com.share.common.util.CryptoUtil;
import com.share.task.service.MetadataReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;

/**
 * 外部数据源 JDBC 连接工厂。
 * <p>从 ds_source 读取连接信息并解密口令，建立只用于本次交换的 DataSource。
 * 连接串与账号不会写入任务日志；异常信息也不包含密码。</p>
 */
@Component
@RequiredArgsConstructor
public class JdbcConnectionFactory {

    private final MetadataReadService metadataReadService;

    @Value("${share.crypto.key:share-exchange-local-crypto-key-change-me}")
    private String cryptoKey;

    private volatile SecretKeySpec secretKey;

    /** 解析数据源连接。 */
    public JdbcEndpoint resolve(Long dataSourceId, String role) {
        if (dataSourceId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, role + "未选择数据源");
        }
        Map<String, Object> source = metadataReadService.findDataSource(dataSourceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        role + "数据源不存在：" + dataSourceId));
        String status = MetadataReadService.pick(source, "status");
        if (status != null && "DISABLED".equalsIgnoreCase(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, role + "数据源已停用：" + dataSourceId);
        }
        String type = MetadataReadService.pick(source, "sourceType", "source_type", "type");
        String url = JdbcConnectorSupport.buildUrl(type,
                MetadataReadService.pick(source, "host"),
                toInteger(MetadataReadService.pick(source, "port")),
                MetadataReadService.pick(source, "dbName", "db_name"),
                MetadataReadService.pick(source, "jdbcUrl", "jdbc_url"));
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    role + "数据源缺少 JDBC 连接串或主机地址：" + dataSourceId);
        }
        String username = MetadataReadService.pick(source, "username", "user");
        String password = decryptPassword(MetadataReadService.pick(source,
                "passwordCipher", "password_cipher"));
        String driverClass = JdbcConnectorSupport.driverClass(type);
        if (driverClass != null) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        role + "数据源 JDBC 驱动未安装：" + driverClass);
            }
        }

        Properties properties = new Properties();
        if (username != null && !username.isBlank()) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        DriverManager.setLoginTimeout(8);
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            if (!connection.isValid(8)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        role + "数据源连接校验失败：" + redact(url));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    role + "数据源连接失败：" + redact(url) + "，" + summarize(e));
        } finally {
            DriverManager.setLoginTimeout(0);
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, properties);
        if (driverClass != null) {
            dataSource.setDriverClassName(driverClass);
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseDialect dialect = DatabaseDialect.detect(jdbcTemplate);
        return new JdbcEndpoint(dataSourceId,
                MetadataReadService.pick(source, "sourceCode", "source_code"),
                MetadataReadService.pick(source, "sourceName", "source_name", "name"),
                type, redact(url), jdbcTemplate, dialect);
    }

    private String decryptPassword(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        if (secretKey == null) {
            secretKey = CryptoUtil.deriveKey(cryptoKey);
        }
        try {
            return CryptoUtil.decrypt(cipher, secretKey);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "数据源口令解密失败，请重新保存数据源口令");
        }
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

    private String redact(String url) {
        String redacted = url == null ? null : url.replaceAll("(?i)(password|pwd)=[^;&]*", "$1=***");
        return redacted != null && redacted.length() > 220 ? redacted.substring(0, 220) + "…" : redacted;
    }

    private String summarize(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) + "…" : message;
    }

    /** 一次交换使用的数据源句柄。 */
    public record JdbcEndpoint(Long id,
                               String code,
                               String name,
                               String type,
                               String redactedUrl,
                               JdbcTemplate jdbc,
                               DatabaseDialect dialect) {
    }
}
