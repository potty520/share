package com.share.share.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 共享 API 定义（FR-07，表 {@code shr_api}）。
 * <p>一个共享 API 对应 API 网关中的一条独立路由：绑定目录资源（catalogId）、数据源（sourceId）
 * 与挂接表（sourceTable），声明查询参数、返回字段、脱敏字段与 QPS 限额；上线时生成网关路由配置
 * 并持久化到 {@code routeConfig}。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "shr_api",
        uniqueConstraints = @UniqueConstraint(name = "uk_shr_api_code", columnNames = "api_code"),
        indexes = {
                @Index(name = "idx_shr_api_status", columnList = "status"),
                @Index(name = "idx_shr_api_catalog", columnList = "catalog_id")
        })
public class ShareApi extends BaseEntity {

    /** 服务编码（全局唯一，开放调用路径 /api/open/v1/{apiCode} 使用） */
    @Column(name = "api_code", nullable = false, length = 64)
    private String apiCode;

    /** 服务名称 */
    @Column(name = "api_name", nullable = false, length = 128)
    private String apiName;

    /** 绑定的目录资源 ID（share-catalog 的 cat_catalog.id，跨域只读引用） */
    @Column(name = "catalog_id")
    private Long catalogId;

    /** 目录资源名称（冗余存储，保证目录不可读时仍可展示） */
    @Column(name = "catalog_name", length = 128)
    private String catalogName;

    /** 绑定的数据源 ID（share-catalog 的 ds_source.id） */
    @Column(name = "source_id")
    private Long sourceId;

    /** 挂接的物理表名（从平台库查询真实数据） */
    @Column(name = "source_table", length = 128)
    private String sourceTable;

    /** HTTP 方法：GET / POST */
    @Column(name = "http_method", length = 16)
    private String httpMethod = "GET";

    /** 参数定义 JSON 数组：[{name,type,required,desc,example,column,op}] */
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** 返回字段（逗号分隔，按声明顺序输出） */
    @Column(name = "return_fields", columnDefinition = "TEXT")
    private String returnFields;

    /** 需脱敏字段（逗号分隔） */
    @Column(name = "mask_fields", columnDefinition = "TEXT")
    private String maskFields;

    /** 单凭据 QPS 限额 */
    @Column(name = "qps_limit")
    private Integer qpsLimit = 100;

    /** 状态：DRAFT / ONLINE / OFFLINE */
    @Column(name = "status", length = 20)
    private String status = "DRAFT";

    /** 上线时间 */
    @Column(name = "online_time")
    private LocalDateTime onlineTime;

    /** 累计调用次数（计量） */
    @Column(name = "call_count")
    private Long callCount = 0L;

    /** 最近一次调用时间 */
    @Column(name = "last_call_time")
    private LocalDateTime lastCallTime;

    /** 责任人 */
    @Column(name = "owner", length = 64)
    private String owner;

    /** 归属部门 */
    @Column(name = "dept_id")
    private Long deptId;

    /** 备注 */
    @Column(name = "remark", length = 500)
    private String remark;

    /** 网关路由配置 JSON（上线时生成，下线时置为禁用态） */
    @Column(name = "route_config", columnDefinition = "TEXT")
    private String routeConfig;

    /** 对外 API 版本标签（请求头 X-Api-Version 可指定），默认 v1 */
    @Column(name = "api_version", length = 20)
    private String apiVersion = "v1";

    /** 内部版本号，每次发布/回滚自增，用于版本快照排序 */
    @Column(name = "version_no")
    private Integer versionNo = 1;

    /** 最近一次发布说明 */
    @Column(name = "version_note", length = 255)
    private String versionNote;

    /** APISIX 路由 ID（同步成功后回写） */
    @Column(name = "gateway_route_id", length = 128)
    private String gatewayRouteId;

    /** APISIX 同步状态：SYNCED / DISABLED / FAILED */
    @Column(name = "gateway_sync_status", length = 32)
    private String gatewaySyncStatus;

    /** APISIX 最近同步时间 */
    @Column(name = "gateway_sync_time")
    private LocalDateTime gatewaySyncTime;

    /** APISIX 最近同步错误 */
    @Column(name = "gateway_error", length = 500)
    private String gatewayError;
}
