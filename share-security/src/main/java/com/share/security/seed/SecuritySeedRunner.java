package com.share.security.seed;

import com.share.common.constant.SeedConstants;
import com.share.common.util.IdGen;
import com.share.security.entity.SecClassifyTag;
import com.share.security.entity.SecClassifyTemplate;
import com.share.security.entity.SecKeyMeta;
import com.share.security.entity.SecMaskRule;
import com.share.security.repository.SecClassifyTagRepository;
import com.share.security.repository.SecClassifyTemplateRepository;
import com.share.security.repository.SecKeyMetaRepository;
import com.share.security.repository.SecMaskRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 安全域种子数据（FR-10）：1 个四级分级模板、4 条常用脱敏规则、2 个密钥元数据、演示分级标签。
 * <p>规则覆盖手机号/身份证/姓名/地址四类高频敏感字段，可直接被交换任务与共享 API 引用。</p>
 */
@Slf4j
@Component
@Order(15)
@RequiredArgsConstructor
public class SecuritySeedRunner implements CommandLineRunner {

    private final SecClassifyTemplateRepository templateRepository;
    private final SecMaskRuleRepository maskRuleRepository;
    private final SecKeyMetaRepository keyMetaRepository;
    private final SecClassifyTagRepository tagRepository;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("share.seed.enabled=false，跳过安全域种子数据");
            return;
        }
        try {
            seedTemplate();
            seedMaskRules();
            seedKeys();
            seedTags();
            log.info("安全域种子数据就绪：模板 {} 个、脱敏规则 {} 条、密钥 {} 个、分级标签 {} 条",
                    templateRepository.count(), maskRuleRepository.count(),
                    keyMetaRepository.count(), tagRepository.count());
        } catch (Exception e) {
            log.error("安全域种子数据初始化失败: {}", e.getMessage(), e);
        }
    }

    /** 1 个通用四级分类分级模板。 */
    private void seedTemplate() {
        if (templateRepository.count() > 0) {
            return;
        }
        SecClassifyTemplate template = new SecClassifyTemplate();
        template.setTemplateCode("CLASSIFY-GENERAL");
        template.setTemplateName("通用数据分类分级模板");
        template.setDescription("政务共享数据四级分类分级：公开 / 内部 / 敏感 / 核心，分级结果作为审批与脱敏依据");
        template.setLevelDefs("["
                + "{\"level\":1,\"name\":\"公开\",\"color\":\"#67C23A\",\"description\":\"可对社会公开的数据\"},"
                + "{\"level\":2,\"name\":\"内部\",\"color\":\"#409EFF\",\"description\":\"部门内部共享的数据\"},"
                + "{\"level\":3,\"name\":\"敏感\",\"color\":\"#E6A23C\",\"description\":\"含个人或企业敏感信息，需审批并脱敏\"},"
                + "{\"level\":4,\"name\":\"核心\",\"color\":\"#F56C6C\",\"description\":\"核心数据，需安全管理员审批且强制加密\"}"
                + "]");
        template.setStatus(1);
        template.setBuiltin(true);
        templateRepository.save(template);
        log.info("初始化分类分级模板: CLASSIFY-GENERAL");
    }

    /** 4 条脱敏规则：手机号遮蔽、身份证遮蔽、姓名替换、地址泛化。 */
    private void seedMaskRules() {
        if (maskRuleRepository.count() > 0) {
            return;
        }
        maskRuleRepository.save(buildRule("SEC-MASK-PHONE", "手机号遮蔽", "MASK", "3,4", "phone",
                "^1[3-9]\\d{9}$", 1, "保留前 3 位后 4 位，如 138****1234"));
        maskRuleRepository.save(buildRule("SEC-MASK-IDCARD", "身份证号遮蔽", "MASK", "6,4", "idCard",
                "^\\d{17}[\\dXx]$", 2, "保留前 6 位（地区码）后 4 位，如 330100********1234"));
        maskRuleRepository.save(buildRule("SEC-MASK-NAME", "姓名替换", "REPLACE", "***", "name",
                null, 1, "姓名统一替换为 ***，适用于低敏展示场景"));
        maskRuleRepository.save(buildRule("SEC-MASK-ADDR", "地址泛化", "GENERALIZE", "ADDR", "address",
                null, 2, "地址截断到区/县一级，如 杭州市西湖区***"));
        log.info("初始化脱敏规则 4 条");
    }

    /** 2 个密钥元数据（只登记元数据，不保存明文密钥）。 */
    private void seedKeys() {
        if (keyMetaRepository.count() > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        SecKeyMeta dataKey = new SecKeyMeta();
        dataKey.setKeyAlias("data-encryption-key");
        dataKey.setAlgorithm("AES-256-GCM");
        dataKey.setKeyStatus("ACTIVE");
        dataKey.setKeyVersion(1);
        dataKey.setKeyFingerprint(fingerprint());
        dataKey.setLastRotatedTime(now.minusDays(30));
        dataKey.setNextRotateTime(now.plusDays(60));
        dataKey.setRotateDays(90);
        dataKey.setDescription("数据落盘加密主密钥（由 KMS 托管，本表仅存元数据）");
        keyMetaRepository.save(dataKey);

        SecKeyMeta fileKey = new SecKeyMeta();
        fileKey.setKeyAlias("file-encryption-key");
        fileKey.setAlgorithm("SM4-CBC");
        fileKey.setKeyStatus("ACTIVE");
        fileKey.setKeyVersion(1);
        fileKey.setKeyFingerprint(fingerprint());
        fileKey.setLastRotatedTime(now.minusDays(10));
        fileKey.setNextRotateTime(now.plusDays(80));
        fileKey.setRotateDays(90);
        fileKey.setDescription("文件交换加密密钥（国密 SM4，本表仅存元数据）");
        keyMetaRepository.save(fileKey);
        log.info("初始化密钥元数据 2 个");
    }

    /** 演示分级标签：4 个目录级 + 2 个字段级（目录 id 与 share-catalog 种子对齐）。 */
    private void seedTags() {
        if (tagRepository.count() > 0) {
            return;
        }
        Long templateId = templateRepository.findByTemplateCodeAndDeletedFalse("CLASSIFY-GENERAL")
                .map(SecClassifyTemplate::getId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        tagRepository.save(buildTag("CATALOG", 1L, "企业基础信息", templateId, null, 3, "敏感", now));
        tagRepository.save(buildTag("CATALOG", 2L, "自然人基础信息", templateId, null, 4, "核心", now));
        tagRepository.save(buildTag("CATALOG", 3L, "社保参保信息", templateId, null, 3, "敏感", now));
        tagRepository.save(buildTag("CATALOG", 4L, "市场主体登记信息", templateId, null, 2, "内部", now));
        tagRepository.save(buildTag("FIELD", 1L, "企业基础信息-法定代表人身份证号", templateId, "legalIdCard", 4, "核心", now));
        tagRepository.save(buildTag("FIELD", 2L, "自然人基础信息-手机号", templateId, "phone", 3, "敏感", now));
        log.info("初始化演示分级标签 6 条");
    }

    private SecMaskRule buildRule(String code, String name, String maskType, String param, String targetField,
                                  String matchPattern, int levelMin, String description) {
        SecMaskRule rule = new SecMaskRule();
        rule.setRuleCode(code);
        rule.setRuleName(name);
        rule.setMaskType(maskType);
        rule.setParam(param);
        rule.setTargetField(targetField);
        rule.setMatchPattern(matchPattern);
        rule.setLevelMin(levelMin);
        rule.setEnabled(true);
        rule.setDescription(description);
        return rule;
    }

    private SecClassifyTag buildTag(String targetType, Long targetId, String targetName, Long templateId,
                                    String fieldName, int level, String levelName, LocalDateTime now) {
        SecClassifyTag tag = new SecClassifyTag();
        tag.setTargetType(targetType);
        tag.setTargetId(targetId);
        tag.setTargetName(targetName);
        tag.setTemplateId(templateId);
        tag.setFieldName(fieldName);
        tag.setLevel(level);
        tag.setLevelName(levelName);
        tag.setTagSource("AUTO");
        tag.setTaggedBy(SeedConstants.ROLE_SECURITY);
        tag.setTaggedTime(now);
        tag.setRemark("演示种子数据");
        return tag;
    }

    private String fingerprint() {
        return IdGen.sha256(IdGen.uuid()).substring(0, 32);
    }
}
