package com.share.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据源新增/修改入参。
 * <p>口令可以放在 {@code password}（明文，服务端加密入库）或 {@code passwordCipher}（兼容前端直接回传），
 * 若传入 {@code ******} 掩码则视为“不修改口令”。</p>
 */
@Getter
@Setter
public class DataSourceDTO {

    @NotBlank(message = "数据源编码不能为空")
    @Size(max = 64, message = "数据源编码最长 64 字符")
    private String sourceCode;

    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 128, message = "数据源名称最长 128 字符")
    private String sourceName;

    @NotBlank(message = "数据源类型不能为空")
    private String sourceType;

    private String host;

    private Integer port;

    private String dbName;

    private String username;

    /** 明文口令（可选） */
    private String password;

    /** 口令密文位（兼容回显值，不会明文落库） */
    private String passwordCipher;

    private String jdbcUrl;

    private String filePath;

    /** 接口类数据源地址（HTTP/WebService） */
    private String apiUrl;

    /** 扩展配置：可传 JSON 字符串或对象 */
    private Object extraConfig;

    private Long deptId;

    private String deptName;

    private String owner;

    /** 密级 1~4 */
    private Integer secretLevel;

    /** 状态：ENABLED / DISABLED，新增缺省 ENABLED */
    private String status;

    private String remark;
}
