package com.share.share.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 共享 API 查询参数定义（FR-07，表 {@code shr_api_param}）。
 * <p>由 {@code shr_api.paramsJson} 解析后规范化落库，用于在线文档生成与开放调用参数白名单校验。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "shr_api_param", indexes = @Index(name = "idx_shr_api_param_api", columnList = "api_id"))
public class ShareApiParam extends BaseEntity {

    /** 所属共享 API */
    @Column(name = "api_id", nullable = false)
    private Long apiId;

    /** 参数名（开放调用时的查询参数名） */
    @Column(name = "param_name", nullable = false, length = 64)
    private String paramName;

    /** 参数类型：string / int / long / decimal / date / bool */
    @Column(name = "param_type", length = 32)
    private String paramType = "string";

    /** 是否必填（列名避开数据库关键字） */
    @Column(name = "is_required")
    private Boolean required = false;

    /** 默认值 */
    @Column(name = "default_value", length = 128)
    private String defaultValue;

    /** 参数说明 */
    @Column(name = "param_desc", length = 255)
    private String paramDesc;

    /** 示例值 */
    @Column(name = "example_value", length = 128)
    private String exampleValue;

    /** 对应物理列名（为空时与 paramName 相同） */
    @Column(name = "column_name", length = 64)
    private String columnName;

    /** 过滤算子：eq / ne / like / ge / le / gt / lt */
    @Column(name = "op", length = 16)
    private String op = "eq";

    /** 排序号 */
    @Column(name = "sort_no")
    private Integer sortNo = 0;
}
