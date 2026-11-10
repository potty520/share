package com.share.catalog.seed;

import com.share.catalog.entity.CatalogItem;
import com.share.catalog.entity.CatalogResource;
import com.share.catalog.entity.CatalogVersion;
import com.share.catalog.entity.Classify;
import com.share.catalog.entity.ConnectorType;
import com.share.catalog.entity.DataSource;
import com.share.catalog.entity.DataSourceLog;
import com.share.catalog.repository.CatalogItemRepository;
import com.share.catalog.repository.CatalogResourceRepository;
import com.share.catalog.repository.CatalogVersionRepository;
import com.share.catalog.repository.ClassifyRepository;
import com.share.catalog.repository.ConnectorTypeRepository;
import com.share.catalog.repository.DataSourceLogRepository;
import com.share.catalog.repository.DataSourceRepository;
import com.share.catalog.support.CatalogSnapshots;
import com.share.catalog.support.SourceTypes;
import com.share.common.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源 / 数据目录种子数据（FR-01 / FR-03）。
 * <p>初始化：连接器类型字典 → 5 个演示数据源（含变更历史）→ 双维度分类树 → 9 个目录资源
 * （6 个已发布 + 2 个待审核 + 1 个草稿，含数据项、敏感字段、历史版本）+ 4 张样例数据表。</p>
 * <p>执行顺序 @Order(20)：晚于 share-auth（组织已就绪），早于 task/share/ops。</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class CatalogSeedRunner implements CommandLineRunner, Ordered {

    /** 演示部门（与 SeedConstants 一致，避免依赖 share-auth 模块） */
    private static final Map<Long, String> DEPTS = Map.of(
            1L, "数据中心",
            2L, "财务部",
            3L, "运营管理部",
            4L, "人力资源部",
            9L, "平台运维部");

    private final DataSourceRepository sourceRepository;
    private final DataSourceLogRepository sourceLogRepository;
    private final ConnectorTypeRepository connectorTypeRepository;
    private final ClassifyRepository classifyRepository;
    private final CatalogResourceRepository catalogRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final CatalogVersionRepository catalogVersionRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Value("${share.crypto.key:share-exchange-local-crypto-key-change-me}")
    private String cryptoKey;

    private volatile SecretKeySpec secretKey;

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("[catalog] share.seed.enabled=false，跳过目录与数据源种子数据");
            return;
        }
        // 各步骤独立容错：单块失败不影响应用启动
        try {
            seedConnectorTypes();
        } catch (Exception e) {
            log.warn("[catalog] 连接器类型种子初始化失败: {}", e.getMessage());
        }
        try {
            seedSources();
        } catch (Exception e) {
            log.warn("[catalog] 数据源种子初始化失败: {}", e.getMessage());
        }
        Map<String, Long> classifyIds = new LinkedHashMap<>();
        try {
            classifyIds = seedClassifies();
        } catch (Exception e) {
            log.warn("[catalog] 分类树种子初始化失败: {}", e.getMessage());
        }
        try {
            seedCatalogs(classifyIds);
        } catch (Exception e) {
            log.warn("[catalog] 目录资源种子初始化失败: {}", e.getMessage());
        }
        log.info("[catalog] 种子数据就绪：数据源 {} 个，分类 {} 个，目录 {} 个，版本 {} 个",
                sourceRepository.countByDeletedFalse(), classifyRepository.countByDeletedFalse(),
                catalogRepository.countByDeletedFalse(), catalogVersionRepository.countByDeletedFalse());
    }

    // ==================== 连接器类型 ====================

    private void seedConnectorTypes() {
        if (connectorTypeRepository.countByDeletedFalse() > 0) {
            return;
        }
        int sort = 10;
        for (SourceTypes.SourceTypeDef def : SourceTypes.all()) {
            ConnectorType type = new ConnectorType();
            type.setTypeCode(def.getCode());
            type.setTypeName(def.getName());
            type.setCategory(def.getCategory());
            type.setDefaultPort(def.getDefaultPort());
            type.setDriverClass(def.getDriverClass());
            type.setUrlTemplate(def.getUrlTemplate());
            type.setRequireHost(def.isRequireHost());
            type.setRequirePath(def.isRequirePath());
            type.setDescription(def.getDescription());
            type.setSortNo(sort);
            type.setStatus("ENABLED");
            sort += 10;
            connectorTypeRepository.save(type);
        }
        log.info("[catalog] 已初始化 {} 种数据源连接器类型", SourceTypes.all().size());
    }

    // ==================== 数据源 ====================

    private void seedSources() {
        if (sourceRepository.countByDeletedFalse() > 0) {
            return;
        }
        saveSource("DS-TAX-MYSQL", "财务部业务库(MySQL)", "MYSQL", "10.20.31.15", 3306, "taxdb",
                "tax_ro", "Tax@2026", null, null, 2L, "王建国", 3,
                "{\"charset\":\"utf8mb4\",\"connectTimeout\":5000,\"useSSL\":false}",
                "承载全市企业纳税申报、发票与纳税信用数据");
        saveSource("DS-MARKET-PG", "运营管理部登记库(PostgreSQL)", "POSTGRESQL", "10.20.32.21", 5432, "marketdb",
                "mkt_reader", "Mkt@2026", null, null, 3L, "李慧", 3,
                "{\"schema\":\"public\",\"fetchSize\":1000}",
                "市场主体登记、变更与年报数据");
        saveSource("DS-HRSS-FILE", "人力资源部社保文件交换区(SFTP)", "SFTP", "10.20.33.9", 22, null,
                "hrss_sftp", "Hrss@2026", "/data/hrss/share", null, 4L, "张伟", 3,
                "{\"authType\":\"password\",\"charset\":\"UTF-8\",\"passiveMode\":true}",
                "社保参保、医保参保明细文件按日落地");
        saveSource("DS-BUS-KAFKA", "数据总线(Kafka)", "KAFKA", "10.20.30.8", 9092, "data-bus",
                "data_client", "Kafka@2026", null, null, 1L, "陈晓", 3,
                "{\"topic\":\"data-bus\",\"groupId\":\"share-exchange\",\"securityProtocol\":\"SASL_PLAINTEXT\"}",
                "跨部门实时数据交换消息总线");
        saveSource("DS-OPEN-HTTP", "市信用信息开放接口(HTTP)", "HTTP", "10.20.30.20", 8080, null,
                "credit_client", "Open@2026", null, "http://10.20.30.20:8080/api/open/credit", 1L, "刘洋", 2,
                "{\"method\":\"GET\",\"contentType\":\"application/json\",\"timeoutMs\":3000}",
                "公共信用信息开放查询接口（信用名单、信用评价）");
        log.info("[catalog] 已初始化 5 个演示数据源");
    }

    private DataSource saveSource(String code, String name, String type, String host, Integer port, String dbName,
                                  String username, String password, String filePath, String apiUrl,
                                  Long deptId, String owner, Integer level, String extraConfig, String remark) {
        DataSource source = new DataSource();
        source.setSourceCode(code);
        source.setSourceName(name);
        source.setSourceType(type);
        source.setHost(host);
        source.setPort(port);
        source.setDbName(dbName);
        source.setUsername(username);
        if (password != null) {
            source.setPasswordCipher(CryptoUtil.encrypt(password, key()));
        }
        source.setFilePath(filePath);
        source.setApiUrl(apiUrl);
        source.setExtraConfig(extraConfig);
        source.setDeptId(deptId);
        source.setDeptName(DEPTS.get(deptId));
        source.setOwner(owner);
        source.setSecretLevel(level);
        source.setStatus("ENABLED");
        source.setRemark(remark);
        if (SourceTypes.isDatabase(type) && host != null && port != null && dbName != null) {
            String template = SourceTypes.of(type).getUrlTemplate();
            if (template != null) {
                source.setJdbcUrl(template.replace("{host}", host)
                        .replace("{port}", String.valueOf(port))
                        .replace("{dbName}", dbName));
            }
        }
        DataSource saved = sourceRepository.save(source);
        DataSourceLog history = new DataSourceLog();
        history.setSourceId(saved.getId());
        history.setSourceCode(saved.getSourceCode());
        history.setSourceName(saved.getSourceName());
        history.setAction("CREATE");
        history.setActionDesc("新增数据源");
        history.setDetail("编码=" + code + "，类型=" + type + "，归属部门=" + source.getDeptName());
        history.setResult("SUCCESS");
        sourceLogRepository.save(history);
        return saved;
    }

    private SecretKeySpec key() {
        if (secretKey == null) {
            secretKey = CryptoUtil.deriveKey(cryptoKey);
        }
        return secretKey;
    }

    // ==================== 分类树 ====================

    private Map<String, Long> seedClassifies() {
        Map<String, Long> ids = new LinkedHashMap<>();
        if (classifyRepository.countByDeletedFalse() > 0) {
            for (Classify entity : classifyRepository.findByDeletedFalseOrderBySortNoAscIdAsc()) {
                if (entity.getCode() != null) {
                    ids.put(entity.getCode(), entity.getId());
                }
            }
            return ids;
        }
        // 主题维度（6 个节点，含 2 个二级节点）
        ids.put("SUB-BUS", saveClassify(null, "综合业务", "SUBJECT", "SUB-BUS", 10, "跨部门综合业务类数据资源"));
        Long market = saveClassify(null, "市场运营", "SUBJECT", "SUB-MARKET", 20, "供应商与客户等市场主体数据资源");
        ids.put("SUB-MARKET", market);
        ids.put("SUB-MARKET-ENT", saveClassify(market, "企业登记", "SUBJECT", "SUB-MARKET-ENT", 21, "企业登记注册基础信息"));
        Long live = saveClassify(null, "员工保障", "SUBJECT", "SUB-LIVE", 30, "社保、医保、公积金等员工保障类数据资源");
        ids.put("SUB-LIVE", live);
        ids.put("SUB-LIVE-SOC", saveClassify(live, "社会保障", "SUBJECT", "SUB-LIVE-SOC", 31, "社会保险参保与待遇数据"));
        ids.put("SUB-CREDIT", saveClassify(null, "信用体系", "SUBJECT", "SUB-CREDIT", 40, "公共信用与信用评价数据"));
        // 部门维度（4 个节点）
        ids.put("DEPT-DATA", saveClassify(null, "数据中心", "DEPT", "DEPT-DATA", 10, "平台与数据主管部门"));
        ids.put("DEPT-TAX", saveClassify(null, "财务部", "DEPT", "DEPT-TAX", 20, "财务数据提供方"));
        ids.put("DEPT-MARKET", saveClassify(null, "运营管理部", "DEPT", "DEPT-MARKET", 30, "市场运营数据提供方"));
        ids.put("DEPT-HRSS", saveClassify(null, "人力资源部", "DEPT", "DEPT-HRSS", 40, "人力资源与社保数据提供方"));
        log.info("[catalog] 已初始化分类树 {} 个节点", ids.size());
        return ids;
    }

    private Long saveClassify(Long parentId, String name, String dimension, String code, int sortNo, String description) {
        Classify entity = new Classify();
        entity.setParentId(parentId);
        entity.setName(name);
        entity.setDimension(dimension);
        entity.setCode(code);
        entity.setSortNo(sortNo);
        entity.setDescription(description);
        return classifyRepository.save(entity).getId();
    }

    // ==================== 目录资源 ====================

    private void seedCatalogs(Map<String, Long> classifyIds) {
        if (catalogRepository.countByDeletedFalse() > 0) {
            return;
        }
        createDemoTables();
        Long dsTax = sourceId("DS-TAX-MYSQL");
        Long dsMarket = sourceId("DS-MARKET-PG");
        Long dsFile = sourceId("DS-HRSS-FILE");
        Long dsHttp = sourceId("DS-OPEN-HTTP");
        LocalDateTime now = LocalDateTime.now();

        // ---------- 1. 企业基础信息（已发布，V2） ----------
        try {
            CatalogResource entity = newCatalog("CAT-ENT-BASE", "企业基础信息",
                    "覆盖全市企业登记注册的基础信息，含统一社会信用代码、法定代表人、注册资本、注册地址等，支撑跨部门企业信息核验。",
                    classifyIds.get("SUB-MARKET-ENT"), 3L, "CONDITIONAL", 3, dsMarket, "POSTGRESQL",
                    "cat_demo_enterprise", null, null, "每日", "运营管理部数据资源处 0571-87654321",
                    "企业,登记,法人", "行业共享目录标准对齐");
            entity = publishCatalog(entity, 2, now.minusDays(3));
            saveItems(entity.getId(), new Object[][]{
                    {"uscc", "VARCHAR(18)", "统一社会信用代码", "91330100MA2AB1CD3E", false, null},
                    {"ent_name", "VARCHAR(128)", "企业名称", "杭州示例科技有限公司", false, null},
                    {"legal_person", "VARCHAR(32)", "法定代表人", "张明", true, "REPLACE_NAME"},
                    {"reg_capital", "VARCHAR(32)", "注册资本(万元)", "5000.00", false, null},
                    {"establish_date", "DATE", "成立日期", "2015-06-18", false, null},
                    {"reg_address", "VARCHAR(200)", "注册地址", "杭州市西湖区示例路100号", true, "GENERALIZE_ADDR"},
                    {"contact_phone", "VARCHAR(20)", "联系电话", "13905710001", true, "MASK_PHONE"},
                    {"industry", "VARCHAR(64)", "所属行业", "软件和信息技术服务业", false, null},
                    {"biz_status", "VARCHAR(16)", "经营状态", "存续", false, null}
            });
            saveVersion(entity, 1, "PUBLISH", "初始版本发布（含 8 个数据项）", now.minusDays(30));
            saveVersion(entity, 2, "PUBLISH", "新增联系电话字段并补充数据项说明", now.minusDays(3));
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 企业基础信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 2. 自然人基础信息（已发布，V1） ----------
        try {
            CatalogResource entity = newCatalog("CAT-PERSON-BASE", "自然人基础信息",
                    "全市自然人基础信息，含姓名、身份证号、手机号、户籍地址等，仅限依法履职场景有条件共享。",
                    classifyIds.get("SUB-LIVE"), 1L, "CONDITIONAL", 4, dsTax, "MYSQL",
                    "cat_demo_person", null, null, "每日", "数据中心数据共享处 0571-88880001",
                    "自然人,人口,基础信息", "涉及个人敏感信息，共享须经安全管理员审核");
            entity = publishCatalog(entity, 1, now.minusDays(20));
            saveItems(entity.getId(), new Object[][]{
                    {"person_name", "VARCHAR(32)", "姓名", "李四", true, "REPLACE_NAME"},
                    {"id_card", "VARCHAR(18)", "身份证号", "330106199001011234", true, "MASK_ID_CARD"},
                    {"phone", "VARCHAR(20)", "手机号", "13805710002", true, "MASK_PHONE"},
                    {"gender", "VARCHAR(8)", "性别", "男", false, null},
                    {"birth_date", "DATE", "出生日期", "1990-01-01", false, null},
                    {"household_address", "VARCHAR(200)", "户籍地址", "杭州市拱墅区示例街道12号", true, "GENERALIZE_ADDR"},
                    {"work_unit", "VARCHAR(128)", "工作单位", "杭州示例科技有限公司", false, null}
            });
            saveVersion(entity, 1, "PUBLISH", "初始版本发布", now.minusDays(20));
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 自然人基础信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 3. 社保参保信息（已发布，文件类挂接） ----------
        try {
            CatalogResource entity = newCatalog("CAT-SOCIAL-INSUR", "社保参保信息",
                    "企业职工社会保险参保明细，含参保险种、缴费基数与参保状态，按月归集，用于员工福利与待遇核验。",
                    classifyIds.get("SUB-LIVE-SOC"), 4L, "CONDITIONAL", 4, dsFile, "SFTP",
                    null, "/data/hrss/share/insured/insured_202611.csv", null, "每月",
                    "人力资源部信息中心 0571-87770002", "社保,参保,员工服务", "文件类挂接，按批次文件交换");
            entity = publishCatalog(entity, 1, now.minusDays(15));
            saveItems(entity.getId(), new Object[][]{
                    {"insured_name", "VARCHAR(32)", "参保人姓名", "王芳", true, "REPLACE_NAME"},
                    {"insured_id_card", "VARCHAR(18)", "身份证号", "330102198805054321", true, "MASK_ID_CARD"},
                    {"employer_name", "VARCHAR(128)", "参保单位", "杭州示例制造有限公司", false, null},
                    {"insurance_type", "VARCHAR(32)", "参保险种", "企业职工基本养老保险", false, null},
                    {"contribution_base", "DECIMAL(12,2)", "缴费基数", "8200.00", false, null},
                    {"insured_status", "VARCHAR(16)", "参保状态", "正常参保", false, null},
                    {"insured_month", "VARCHAR(8)", "所属年月", "2026-10", false, null}
            });
            saveVersion(entity, 1, "PUBLISH", "初始版本发布", now.minusDays(15));
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 社保参保信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 4. 市场主体登记信息（已发布，V1） ----------
        try {
            CatalogResource entity = newCatalog("CAT-MARKET-REG", "市场主体登记信息",
                    "市场主体登记、变更、年报信息，含主体类型、登记机构、住所与法定代表人，用于供应商与客户分析。",
                    classifyIds.get("SUB-MARKET"), 3L, "CONDITIONAL", 3, dsMarket, "POSTGRESQL",
                    "cat_demo_market", null, null, "每日", "运营管理部登记注册处 0571-87650009",
                    "市场主体,登记,市场运营", "与行业登记标准保持一致");
            entity = publishCatalog(entity, 1, now.minusDays(12));
            saveItems(entity.getId(), new Object[][]{
                    {"market_code", "VARCHAR(64)", "市场主体编码", "MKT202600001", false, null},
                    {"market_name", "VARCHAR(128)", "市场主体名称", "杭州示例商贸有限公司", false, null},
                    {"market_type", "VARCHAR(32)", "主体类型", "有限责任公司", false, null},
                    {"legal_person", "VARCHAR(32)", "法定代表人", "陈昊", true, "REPLACE_NAME"},
                    {"reg_date", "DATE", "登记日期", "2019-03-12", false, null},
                    {"reg_org", "VARCHAR(128)", "登记机构", "杭州市市场监督管理局", false, null},
                    {"address", "VARCHAR(200)", "住所", "杭州市滨江区示例大道8号", true, "GENERALIZE_ADDR"},
                    {"contact_phone", "VARCHAR(20)", "联系电话", "13605710022", true, "MASK_PHONE"}
            });
            saveVersion(entity, 1, "PUBLISH", "初始版本发布", now.minusDays(12));
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 市场主体登记信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 5. 不动产登记信息（已发布，V1） ----------
        try {
            CatalogResource entity = newCatalog("CAT-REALESTATE", "不动产登记信息",
                    "全市不动产登记结果信息，含不动产单元号、权利人、坐落地址与建筑面积，用于房产核验与政策测算。",
                    classifyIds.get("SUB-BUS"), 1L, "CONDITIONAL", 4, dsMarket, "POSTGRESQL",
                    "cat_demo_estate", null, null, "每日", "数据中心数据汇聚组 0571-88880002",
                    "不动产,房产,登记", "权利人信息涉个人隐私，须脱敏后共享");
            entity = publishCatalog(entity, 1, now.minusDays(9));
            saveItems(entity.getId(), new Object[][]{
                    {"estate_no", "VARCHAR(64)", "不动产单元号", "330106002001GB00001F00010001", false, null},
                    {"owner_name", "VARCHAR(32)", "权利人", "赵强", true, "REPLACE_NAME"},
                    {"owner_id_card", "VARCHAR(18)", "权利人证件号", "330104197912123456", true, "MASK_ID_CARD"},
                    {"estate_address", "VARCHAR(200)", "坐落地址", "杭州市西湖区示例花园3幢1单元101室", true, "GENERALIZE_ADDR"},
                    {"area_sqm", "DECIMAL(12,2)", "建筑面积(㎡)", "128.50", false, null},
                    {"use_type", "VARCHAR(32)", "规划用途", "住宅", false, null},
                    {"reg_date", "DATE", "登记日期", "2018-09-20", false, null}
            });
            saveVersion(entity, 1, "PUBLISH", "初始版本发布", now.minusDays(9));
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 不动产登记信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 6. 公共信用信息（已发布，接口类挂接，无条件共享） ----------
        try {
            CatalogResource entity = newCatalog("CAT-CREDIT", "公共信用信息",
                    "公共信用评价与信用名单信息，含信用等级、守信与失信记录数，可无条件共享用于社会化应用。",
                    classifyIds.get("SUB-CREDIT"), 1L, "UNCONDITIONAL", 3, dsHttp, "HTTP",
                    null, null, "http://10.20.30.20:8080/api/open/credit", "实时",
                    "数据中心信用中心 0571-88880003", "信用,信用名单,信用评价", "接口类挂接，按 AppKey 授权调用");
            entity = publishCatalog(entity, 1, now.minusDays(6));
            saveItems(entity.getId(), new Object[][]{
                    {"entity_name", "VARCHAR(128)", "信用主体名称", "杭州示例科技有限公司", false, null},
                    {"uscc", "VARCHAR(18)", "统一社会信用代码", "91330100MA2AB1CD3E", false, null},
                    {"credit_level", "VARCHAR(8)", "信用等级", "A", false, null},
                    {"reward_count", "INTEGER", "守信记录数", "3", false, null},
                    {"punish_count", "INTEGER", "失信记录数", "0", false, null},
                    {"eval_date", "DATE", "评价日期", "2026-10-31", false, null},
                    {"contact_phone", "VARCHAR(20)", "联系电话", "13905710005", true, "MASK_PHONE"}
            });
            saveVersion(entity, 1, "PUBLISH", "初始版本发布", now.minusDays(6));
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 公共信用信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 7. 纳税信用评价信息（待审核） ----------
        try {
            CatalogResource entity = newCatalog("CAT-TAX-CREDIT", "纳税信用评价信息",
                    "企业纳税信用年度评价结果，含信用等级与评价得分，用于金融授信与招投标信用核查。",
                    classifyIds.get("SUB-CREDIT"), 2L, "CONDITIONAL", 3, dsTax, "MYSQL",
                    null, null, null, "每年", "财务部纳税服务处 0571-87660001",
                    "纳税信用,信用评价", "待业务管理员审核");
            entity = submitCatalog(entity, now.minusDays(1));
            saveItems(entity.getId(), new Object[][]{
                    {"taxpayer_name", "VARCHAR(128)", "纳税人名称", "杭州示例科技有限公司", false, null},
                    {"taxpayer_id", "VARCHAR(32)", "纳税人识别号", "91330100MA2AB1CD3E", false, null},
                    {"credit_rating", "VARCHAR(4)", "纳税信用等级", "A", false, null},
                    {"score", "DECIMAL(5,2)", "评价得分", "95.50", false, null},
                    {"eval_year", "VARCHAR(8)", "评价年度", "2025", false, null}
            });
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 纳税信用评价信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 8. 医疗保险参保信息（待审核） ----------
        try {
            CatalogResource entity = newCatalog("CAT-MEDICAL-INSUR", "医疗保险参保信息",
                    "职工基本医疗保险参保与结算信息，用于医疗救助对象核验与费用监测。",
                    classifyIds.get("SUB-LIVE-SOC"), 4L, "CONDITIONAL", 4, dsFile, "SFTP",
                    null, "/data/hrss/share/medical/medical_202611.csv", null, "每月",
                    "人力资源部医保中心 0571-87770003", "医保,参保,员工服务", "含个人敏感信息，需安全管理员审批");
            entity = submitCatalog(entity, now.minusHours(6));
            saveItems(entity.getId(), new Object[][]{
                    {"insured_name", "VARCHAR(32)", "参保人姓名", "孙丽", true, "REPLACE_NAME"},
                    {"insured_id_card", "VARCHAR(18)", "身份证号", "330105199203031122", true, "MASK_ID_CARD"},
                    {"hospital_name", "VARCHAR(128)", "就诊医院", "杭州市第一人民医院", false, null},
                    {"total_amount", "DECIMAL(12,2)", "医疗费用总额", "3860.00", false, null},
                    {"reimburse_amount", "DECIMAL(12,2)", "医保报销金额", "2895.00", false, null}
            });
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 医疗保险参保信息 初始化失败: {}", e.getMessage());
        }

        // ---------- 9. 住房公积金缴存信息（草稿） ----------
        try {
            CatalogResource entity = newCatalog("CAT-HOUSING-FUND", "住房公积金缴存信息",
                    "单位与职工住房公积金缴存、余额信息，用于住房保障资格核验。",
                    classifyIds.get("SUB-LIVE"), 4L, "CONDITIONAL", 3, null, null,
                    null, null, null, "每月", "人力资源部公积金组 0571-87000001",
                    "公积金,缴存,住房", "编目中，数据源待挂接");
            entity.setStatus("DRAFT");
            entity.setVersionNo(1);
            entity = catalogRepository.save(entity);
            saveItems(entity.getId(), new Object[][]{
                    {"person_name", "VARCHAR(32)", "姓名", "周杰", true, "REPLACE_NAME"},
                    {"id_card", "VARCHAR(18)", "身份证号", "330108198506061234", true, "MASK_ID_CARD"},
                    {"account_no", "VARCHAR(32)", "公积金账号", "330100123456789", true, "MASK_ACCOUNT"},
                    {"balance", "DECIMAL(12,2)", "账户余额", "56230.00", false, null},
                    {"monthly_amount", "DECIMAL(12,2)", "月缴存额", "2400.00", false, null}
            });
        } catch (Exception e) {
            log.warn("[catalog] 种子目录 住房公积金缴存信息 初始化失败: {}", e.getMessage());
        }

        log.info("[catalog] 已初始化 9 个演示目录（6 已发布 / 2 待审核 / 1 草稿）");
    }

    /** 创建目录资源（不含状态，由 publish/submit 辅助方法设置）。 */
    private CatalogResource newCatalog(String code, String name, String summary, Long classifyId, Long deptId,
                                       String shareType, Integer level, Long sourceId, String sourceType,
                                       String tableName, String filePath, String apiUrl, String updateFrequency,
                                       String providerContact, String tags, String remark) {
        CatalogResource entity = new CatalogResource();
        entity.setResourceCode(code);
        entity.setResourceName(name);
        entity.setSummary(summary);
        entity.setClassifyId(classifyId);
        entity.setDeptId(deptId);
        entity.setDeptName(DEPTS.get(deptId));
        entity.setShareType(shareType);
        entity.setLevel(level);
        entity.setSourceId(sourceId);
        entity.setSourceType(sourceType);
        entity.setTableName(tableName);
        entity.setFilePath(filePath);
        entity.setApiUrl(apiUrl);
        entity.setUpdateFrequency(updateFrequency);
        entity.setProviderContact(providerContact);
        entity.setTags(tags);
        entity.setRemark(remark);
        entity.setVersionNo(1);
        return catalogRepository.save(entity);
    }

    /** 置为已发布并写入审核信息。 */
    private CatalogResource publishCatalog(CatalogResource entity, int versionNo, LocalDateTime publishTime) {
        entity.setStatus("PUBLISHED");
        entity.setVersionNo(versionNo);
        entity.setPublishTime(publishTime);
        entity.setSubmitTime(publishTime.minusDays(1));
        entity.setAuditBy("bizadmin");
        entity.setAuditTime(publishTime.minusDays(1));
        entity.setAuditComment("目录要素齐全，同意发布共享");
        return catalogRepository.save(entity);
    }

    /** 置为待审核。 */
    private CatalogResource submitCatalog(CatalogResource entity, LocalDateTime submitTime) {
        entity.setStatus("PENDING");
        entity.setVersionNo(1);
        entity.setSubmitTime(submitTime);
        return catalogRepository.save(entity);
    }

    /** 批量写入数据项：每行 {fieldName, fieldType, fieldComment, sampleValue, sensitive, maskRuleCode}。 */
    private void saveItems(Long catalogId, Object[][] definitions) {
        int sort = 10;
        for (Object[] row : definitions) {
            CatalogItem item = new CatalogItem();
            item.setCatalogId(catalogId);
            item.setFieldName(String.valueOf(row[0]));
            item.setFieldType(row[1] == null ? null : String.valueOf(row[1]));
            item.setFieldComment(row[2] == null ? null : String.valueOf(row[2]));
            item.setSampleValue(row[3] == null ? null : String.valueOf(row[3]));
            item.setSensitive(row[4] != null && Boolean.parseBoolean(String.valueOf(row[4])));
            item.setMaskRuleCode(row[5] == null ? null : String.valueOf(row[5]));
            item.setSortNo(sort);
            sort += 10;
            catalogItemRepository.save(item);
        }
    }

    /** 写入版本快照。 */
    private void saveVersion(CatalogResource catalog, int versionNo, String changeType,
                             String changeLog, LocalDateTime publishTime) {
        List<CatalogItem> items = catalogItemRepository
                .findByCatalogIdAndDeletedFalseOrderBySortNoAscIdAsc(catalog.getId());
        CatalogVersion version = new CatalogVersion();
        version.setCatalogId(catalog.getId());
        version.setVersionNo(versionNo);
        version.setChangeType(changeType);
        version.setChangeLog(changeLog);
        version.setSnapshot(CatalogSnapshots.build(catalog, items, versionNo));
        version.setPublishTime(publishTime);
        version.setOperator("system");
        catalogVersionRepository.save(version);
    }

    private Long sourceId(String code) {
        return sourceRepository.findBySourceCodeAndDeletedFalse(code).map(DataSource::getId).orElse(null);
    }

    // ==================== 样例数据表（供目录预览演示真实查询） ====================

    private void createDemoTables() {
        createTable("cat_demo_enterprise", "id integer primary key, uscc varchar(18), ent_name varchar(128), "
                + "legal_person varchar(32), reg_capital varchar(32), establish_date date, reg_address varchar(200), "
                + "contact_phone varchar(20), industry varchar(64), biz_status varchar(16)");
        createTable("cat_demo_person", "id integer primary key, person_name varchar(32), id_card varchar(18), "
                + "phone varchar(20), gender varchar(8), birth_date date, household_address varchar(200), "
                + "work_unit varchar(128)");
        createTable("cat_demo_market", "id integer primary key, market_code varchar(64), market_name varchar(128), "
                + "market_type varchar(32), legal_person varchar(32), reg_date date, reg_org varchar(128), "
                + "address varchar(200), contact_phone varchar(20)");
        createTable("cat_demo_estate", "id integer primary key, estate_no varchar(64), owner_name varchar(32), "
                + "owner_id_card varchar(18), estate_address varchar(200), area_sqm decimal(12,2), "
                + "use_type varchar(32), reg_date date");

        insertRows("cat_demo_enterprise",
                new String[]{"id", "uscc", "ent_name", "legal_person", "reg_capital", "establish_date",
                        "reg_address", "contact_phone", "industry", "biz_status"},
                List.<Object[]>of(
                        new Object[]{1, "91330100MA2AB1CD3E", "杭州示例科技有限公司", "张明", "5000.00", date("2015-06-18"), "杭州市西湖区示例路100号", "13905710001", "软件和信息技术服务业", "存续"},
                        new Object[]{2, "91330100MA2AB1CD3F", "杭州示例制造有限公司", "李强", "12000.00", date("2012-03-05"), "杭州市萧山区示例工业园8号", "13705710011", "通用设备制造业", "存续"},
                        new Object[]{3, "91330100MA2AB1CD3G", "杭州示例商贸有限公司", "陈昊", "800.00", date("2019-03-12"), "杭州市滨江区示例大道8号", "13605710022", "批发业", "存续"},
                        new Object[]{4, "91330100MA2AB1CD3H", "杭州示例建设集团有限公司", "周伟", "30000.00", date("2008-11-20"), "杭州市上城区示例街66号", "13805710033", "房屋建筑业", "存续"},
                        new Object[]{5, "91330100MA2AB1CD3J", "杭州示例医疗器械有限公司", "吴敏", "2000.00", date("2017-07-08"), "杭州市余杭区示例路18号", "13505710044", "医药制造业", "存续"},
                        new Object[]{6, "91330100MA2AB1CD3K", "杭州示例物流有限公司", "郑凯", "1500.00", date("2014-09-15"), "杭州市钱塘区示例物流园3号", "13405710055", "道路运输业", "注销"}));

        insertRows("cat_demo_person",
                new String[]{"id", "person_name", "id_card", "phone", "gender", "birth_date", "household_address", "work_unit"},
                List.<Object[]>of(
                        new Object[]{1, "李四", "330106199001011234", "13805710002", "男", date("1990-01-01"), "杭州市拱墅区示例街道12号", "杭州示例科技有限公司"},
                        new Object[]{2, "王芳", "330102198805054321", "13905710012", "女", date("1988-05-05"), "杭州市西湖区示例里5号", "杭州示例制造有限公司"},
                        new Object[]{3, "赵强", "330104197912123456", "13705710023", "男", date("1979-12-12"), "杭州市上城区示例巷7号", "杭州示例建设集团有限公司"},
                        new Object[]{4, "孙丽", "330105199203031122", "13605710034", "女", date("1992-03-03"), "杭州市滨江区示例路9号", "杭州示例商贸有限公司"},
                        new Object[]{5, "周杰", "330108198506061234", "13505710045", "男", date("1985-06-06"), "杭州市萧山区示例街21号", "杭州示例物流有限公司"},
                        new Object[]{6, "吴静", "330109199511118888", "13405710056", "女", date("1995-11-11"), "杭州市余杭区示例苑2号", "杭州示例医疗器械有限公司"}));

        insertRows("cat_demo_market",
                new String[]{"id", "market_code", "market_name", "market_type", "legal_person", "reg_date",
                        "reg_org", "address", "contact_phone"},
                List.<Object[]>of(
                        new Object[]{1, "MKT202600001", "杭州示例科技有限公司", "有限责任公司", "张明", date("2015-06-18"), "杭州市市场监督管理局", "杭州市西湖区示例路100号", "13905710001"},
                        new Object[]{2, "MKT202600002", "杭州示例制造有限公司", "有限责任公司", "李强", date("2012-03-05"), "杭州市市场监督管理局", "杭州市萧山区示例工业园8号", "13705710011"},
                        new Object[]{3, "MKT202600003", "杭州示例商贸有限公司", "有限责任公司", "陈昊", date("2019-03-12"), "杭州市市场监督管理局", "杭州市滨江区示例大道8号", "13605710022"},
                        new Object[]{4, "MKT202600004", "杭州示例建设集团有限公司", "有限责任公司", "周伟", date("2008-11-20"), "杭州市市场监督管理局", "杭州市上城区示例街66号", "13805710033"},
                        new Object[]{5, "MKT202600005", "杭州示例医疗器械有限公司", "有限责任公司", "吴敏", date("2017-07-08"), "杭州市市场监督管理局", "杭州市余杭区示例路18号", "13505710044"},
                        new Object[]{6, "MKT202600006", "杭州示例物流有限公司", "有限责任公司", "郑凯", date("2014-09-15"), "杭州市市场监督管理局", "杭州市钱塘区示例物流园3号", "13405710055"}));

        insertRows("cat_demo_estate",
                new String[]{"id", "estate_no", "owner_name", "owner_id_card", "estate_address", "area_sqm",
                        "use_type", "reg_date"},
                List.<Object[]>of(
                        new Object[]{1, "330106002001GB00001F00010001", "赵强", "330104197912123456", "杭州市西湖区示例花园3幢1单元101室", new BigDecimal("128.50"), "住宅", date("2018-09-20")},
                        new Object[]{2, "330106002001GB00001F00010002", "孙丽", "330105199203031122", "杭州市西湖区示例花园5幢2单元202室", new BigDecimal("96.30"), "住宅", date("2019-05-11")},
                        new Object[]{3, "330108002002GB00002F00020001", "周杰", "330108198506061234", "杭州市萧山区示例大厦A座1201室", new BigDecimal("210.00"), "商业", date("2020-08-18")},
                        new Object[]{4, "330109002003GB00003F00030001", "吴静", "330109199511118888", "杭州市余杭区示例苑8幢303室", new BigDecimal("88.60"), "住宅", date("2021-03-09")},
                        new Object[]{5, "330102002004GB00004F00040001", "王芳", "330102198805054321", "杭州市上城区示例里5号1单元401室", new BigDecimal("105.20"), "住宅", date("2017-12-01")},
                        new Object[]{6, "330104002005GB00005F00050001", "李四", "330106199001011234", "杭州市拱墅区示例街道12号", new BigDecimal("76.40"), "住宅", date("2016-06-22")}));
    }

    private void createTable(String table, String columns) {
        try {
            jdbcTemplate.execute("create table if not exists " + table + " (" + columns + ")");
        } catch (Exception e) {
            log.warn("[catalog] 样例表 {} 创建失败: {}", table, e.getMessage());
        }
    }

    private void insertRows(String table, String[] columns, List<Object[]> rows) {
        try {
            Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
            if (count != null && count > 0) {
                return;
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(columns.length, "?"));
            String sql = "insert into " + table + " (" + String.join(",", columns) + ") values (" + placeholders + ")";
            jdbcTemplate.batchUpdate(sql, rows);
            log.info("[catalog] 样例表 {} 已写入 {} 行演示数据", table, rows.size());
        } catch (Exception e) {
            log.warn("[catalog] 样例表 {} 写入失败: {}", table, e.getMessage());
        }
    }

    private Date date(String value) {
        return Date.valueOf(value);
    }
}
