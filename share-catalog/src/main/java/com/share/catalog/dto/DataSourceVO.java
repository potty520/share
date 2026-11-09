package com.share.catalog.dto;

import com.share.catalog.entity.DataSource;
import com.share.catalog.support.Dicts;
import com.share.catalog.support.SourceTypes;
import com.share.common.util.JsonUtil;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/** 数据源响应对象（口令一律掩码，不回显明文/密文）。 */
@Getter
@Setter
public class DataSourceVO {

    /** 口令掩码常量 */
    public static final String MASK = "******";

    private Long id;
    private String sourceCode;
    private String sourceName;
    private String sourceType;
    private String typeName;
    /** 接入类别：DB/FILE/MQ/API */
    private String category;
    private String host;
    private Integer port;
    private String dbName;
    private String username;
    /** 固定返回掩码 ******，绝不回显真实口令 */
    private String passwordCipher;
    /** 是否已配置口令 */
    private boolean hasPassword;
    private String jdbcUrl;
    private String filePath;
    /** 接口类数据源地址 */
    private String apiUrl;
    private Object extraConfig;
    private Long deptId;
    private String deptName;
    private String owner;
    private Integer secretLevel;
    private String status;
    private String statusText;
    private LocalDateTime lastTestTime;
    private String lastTestResult;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String createBy;
    private String updateBy;

    /** 实体 → 响应对象（口令脱敏 + 类型/状态文案）。 */
    public static DataSourceVO from(DataSource e) {
        DataSourceVO vo = new DataSourceVO();
        vo.id = e.getId();
        vo.sourceCode = e.getSourceCode();
        vo.sourceName = e.getSourceName();
        vo.sourceType = e.getSourceType();
        vo.typeName = SourceTypes.nameOf(e.getSourceType());
        vo.category = SourceTypes.categoryOf(e.getSourceType());
        vo.host = e.getHost();
        vo.port = e.getPort();
        vo.dbName = e.getDbName();
        vo.username = e.getUsername();
        boolean hasPassword = e.getPasswordCipher() != null && !e.getPasswordCipher().isBlank();
        vo.hasPassword = hasPassword;
        vo.passwordCipher = hasPassword ? MASK : null;
        vo.jdbcUrl = e.getJdbcUrl();
        vo.filePath = e.getFilePath();
        vo.apiUrl = e.getApiUrl();
        vo.extraConfig = parseJson(e.getExtraConfig());
        vo.deptId = e.getDeptId();
        vo.deptName = e.getDeptName();
        vo.owner = e.getOwner();
        vo.secretLevel = e.getSecretLevel();
        vo.status = e.getStatus();
        vo.statusText = Dicts.sourceStatusText(e.getStatus());
        vo.lastTestTime = e.getLastTestTime();
        vo.lastTestResult = e.getLastTestResult();
        vo.remark = e.getRemark();
        vo.createTime = e.getCreateTime();
        vo.updateTime = e.getUpdateTime();
        vo.createBy = e.getCreateBy();
        vo.updateBy = e.getUpdateBy();
        return vo;
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.mapper().readTree(json);
        } catch (Exception ignore) {
            return json;
        }
    }

    /** 兼容：扩展配置也可能是普通 Map（种子数据构造用）。 */
    public static Object mapToJson(Map<String, Object> map) {
        return map == null ? null : JsonUtil.toJson(map);
    }
}
