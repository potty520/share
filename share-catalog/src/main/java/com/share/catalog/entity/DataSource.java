package com.share.catalog.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 数据源登记（FR-01）。
 * <p>连接信息中的口令只落库密文（{@code password_cipher}，AES-GCM），接口层一律以掩码返回。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "ds_source", indexes = {
        @Index(name = "idx_ds_source_code", columnList = "source_code"),
        @Index(name = "idx_ds_source_type", columnList = "source_type")
})
public class DataSource extends BaseEntity {

    /** 数据源编码，全局唯一，如 DS-TAX-MYSQL */
    @Column(name = "source_code", length = 64, nullable = false, unique = true)
    private String sourceCode;

    /** 数据源名称 */
    @Column(name = "source_name", length = 128, nullable = false)
    private String sourceName;

    /** 类型：MYSQL/ORACLE/POSTGRESQL/SQLSERVER/DM/HIVE/CLICKHOUSE/FTP/SFTP/OSS/KAFKA/HTTP/WEBSERVICE */
    @Column(name = "source_type", length = 32, nullable = false)
    private String sourceType;

    /** 主机地址 */
    @Column(length = 128)
    private String host;

    /** 端口 */
    private Integer port;

    /** 库名 / 主题名 */
    @Column(name = "db_name", length = 128)
    private String dbName;

    /** 账号 */
    @Column(length = 128)
    private String username;

    /** 口令密文（AES-GCM，永不回显） */
    @Column(name = "password_cipher", length = 512)
    private String passwordCipher;

    /** JDBC 连接串（可缺省，由类型模板生成） */
    @Column(name = "jdbc_url", columnDefinition = "TEXT")
    private String jdbcUrl;

    /** 文件类数据源的目录/路径 */
    @Column(name = "file_path", length = 512)
    private String filePath;

    /** 接口类数据源的访问地址（HTTP/WebService） */
    @Column(name = "api_url", length = 512)
    private String apiUrl;

    /** 扩展配置（JSON 文本：超时、字符集、认证方式、Topic 等） */
    @Column(name = "extra_config", columnDefinition = "TEXT")
    private String extraConfig;

    /** 归属部门 ID（只读 sys_dept 回填名称） */
    @Column(name = "dept_id")
    private Long deptId;

    /** 归属部门名称（冗余，便于列表展示） */
    @Column(name = "dept_name", length = 64)
    private String deptName;

    /** 责任人 */
    @Column(length = 64)
    private String owner;

    /** 密级 1~4 */
    @Column(name = "secret_level")
    private Integer secretLevel;

    /** 状态：ENABLED / DISABLED */
    @Column(length = 16)
    private String status;

    /** 最近一次连通性测试时间 */
    @Column(name = "last_test_time")
    private LocalDateTime lastTestTime;

    /** 最近一次连通性测试结果（SUCCESS/FAIL + 摘要） */
    @Column(name = "last_test_result", length = 256)
    private String lastTestResult;

    /** 备注 */
    @Column(length = 500)
    private String remark;
}
