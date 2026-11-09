package com.share.catalog.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据源连接器类型字典（FR-01，可选表：ds_connector_type）。
 * <p>由种子数据初始化，供前端下拉与默认端口/驱动提示使用；新增连接器类型只需扩表，无需改代码。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "ds_connector_type")
public class ConnectorType extends BaseEntity {

    /** 类型编码：MYSQL、ORACLE… */
    @Column(name = "type_code", length = 32, nullable = false, unique = true)
    private String typeCode;

    /** 类型名称 */
    @Column(name = "type_name", length = 64, nullable = false)
    private String typeName;

    /** 接入类别：DB（数据库）/ FILE（文件）/ MQ（消息）/ API（接口） */
    @Column(length = 16)
    private String category;

    /** 默认端口 */
    @Column(name = "default_port")
    private Integer defaultPort;

    /** JDBC 驱动类（数据库类） */
    @Column(name = "driver_class", length = 200)
    private String driverClass;

    /** 连接串模板 */
    @Column(name = "url_template", length = 300)
    private String urlTemplate;

    /** 是否需要主机端口 */
    @Column(name = "require_host")
    private Boolean requireHost;

    /** 是否需要文件路径 */
    @Column(name = "require_path")
    private Boolean requirePath;

    /** 说明 */
    @Column(length = 500)
    private String description;

    /** 排序号 */
    @Column(name = "sort_no")
    private Integer sortNo;

    /** 是否启用 */
    @Column(length = 8)
    private String status;
}
