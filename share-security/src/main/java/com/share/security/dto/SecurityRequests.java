package com.share.security.dto;

import java.util.List;

/** share-security 请求体（FR-10）。 */
public final class SecurityRequests {

    private SecurityRequests() {
    }

    /** 分类分级模板新增/修改：levelDefs 支持 JSON 字符串或对象数组。 */
    public record TemplateSaveRequest(String templateCode,
                                      String templateName,
                                      String description,
                                      Object levelDefs,
                                      Integer status) {
    }

    /** 单个分级标签。 */
    public record TagSaveRequest(String targetType,
                                 Long targetId,
                                 String targetName,
                                 Long templateId,
                                 String fieldName,
                                 Integer level,
                                 String levelName,
                                 String remark) {
    }

    /** 批量打标中的单个目标。 */
    public record TagItem(Long targetId,
                          String targetName,
                          String fieldName,
                          Integer level,
                          String levelName,
                          String remark) {
    }

    /** 批量打标：targetType/templateId/level 为默认值，可被单个 TagItem 覆盖。 */
    public record TagBatchRequest(String targetType,
                                  Long templateId,
                                  Integer level,
                                  String levelName,
                                  List<TagItem> tags) {
    }

    /** 脱敏规则新增/修改。 */
    public record MaskRuleSaveRequest(String ruleCode,
                                      String ruleName,
                                      String maskType,
                                      String param,
                                      String targetField,
                                      String matchPattern,
                                      Integer levelMin,
                                      Boolean enabled,
                                      String description) {
    }

    /** 规则试算：可直接指定算法与参数，也可引用已有规则补默认值。 */
    public record MaskTestRequest(String maskType,
                                  String param,
                                  String value,
                                  String ruleCode) {
    }

    /** 密钥元数据新增。 */
    public record KeyCreateRequest(String keyAlias,
                                   String algorithm,
                                   String description,
                                   Integer rotateDays) {
    }
}
