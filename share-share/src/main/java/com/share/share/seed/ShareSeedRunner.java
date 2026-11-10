package com.share.share.seed;

import com.share.common.constant.SeedConstants;
import com.share.common.util.IdGen;
import com.share.common.util.JsonUtil;
import com.share.share.constant.ShareConstants;
import com.share.share.dto.ApiSaveRequest;
import com.share.share.entity.ApprovalApply;
import com.share.share.entity.ApprovalFlowNode;
import com.share.share.entity.ApprovalGrant;
import com.share.share.entity.ShareApi;
import com.share.share.entity.ShareCallStat;
import com.share.share.entity.ShareCredential;
import com.share.share.repository.ApprovalApplyRepository;
import com.share.share.repository.ApprovalFlowNodeRepository;
import com.share.share.repository.ApprovalGrantRepository;
import com.share.share.repository.ShareApiRepository;
import com.share.share.repository.ShareCallStatRepository;
import com.share.share.repository.ShareCredentialRepository;
import com.share.share.service.ShareApiService;
import com.share.share.support.CrossDomainQueryService;
import com.share.share.support.TableMetaCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享服务与审批域种子数据（FR-07 / FR-08）。
 * <p>@Order(40)：在 auth(1) → catalog(20) → task(30) 之后执行，以便按 resource_code/名称
 * 绑定目录资源；跨域查询全部做容错，查不到时退化为 catalogId=null 并保留 catalogName 快照。</p>
 * <p>同时创建 3 张演示业务表（shr_demo_*）放真实样例数据，使共享 API 的开放调用、
 * 在线调试、动态脱敏与计量统计可真实跑通。</p>
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class ShareSeedRunner implements CommandLineRunner {

    private final ShareApiRepository apiRepository;
    private final ShareCredentialRepository credentialRepository;
    private final ShareCallStatRepository statRepository;
    private final ApprovalApplyRepository applyRepository;
    private final ApprovalFlowNodeRepository nodeRepository;
    private final ApprovalGrantRepository grantRepository;
    private final ShareApiService shareApiService;
    private final CrossDomainQueryService crossDomainQueryService;
    private final TableMetaCache tableMetaCache;
    private final JdbcTemplate jdbcTemplate;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("share.seed.enabled=false，跳过共享服务域种子数据");
            return;
        }
        try {
            createDemoTables();
            if (apiRepository.count() > 0) {
                log.info("共享服务域种子数据已存在，跳过初始化");
                return;
            }
            seedApis();
            seedCredentials();
            seedPendingApplies();
            seedApprovedApply();
            seedCallStats();
            log.info("共享服务域种子数据初始化完成：3 个共享服务、3 条凭据、3 条申请、20 条调用统计");
        } catch (Exception e) {
            // 种子失败不得阻断平台启动
            log.error("共享服务域种子数据初始化失败", e);
        }
    }

    // ==================== 演示业务表 ====================

    private void createDemoTables() {
        execute("""
                create table if not exists shr_demo_enterprise (
                    id bigint auto_increment primary key,
                    ent_name varchar(200),
                    credit_code varchar(64),
                    legal_person varchar(64),
                    reg_capital decimal(18,2),
                    industry varchar(64),
                    address varchar(255),
                    phone varchar(32),
                    reg_date date,
                    status varchar(32)
                )""");
        execute("""
                create table if not exists shr_demo_hrss (
                    id bigint auto_increment primary key,
                    person_name varchar(64),
                    id_card varchar(32),
                    phone varchar(32),
                    company varchar(200),
                    insur_type varchar(64),
                    insur_month varchar(16),
                    base_amount decimal(18,2),
                    status varchar(32)
                )""");
        execute("""
                create table if not exists shr_demo_credit (
                    id bigint auto_increment primary key,
                    ent_name varchar(200),
                    credit_code varchar(64),
                    credit_score int,
                    credit_level varchar(16),
                    blacklist varchar(8),
                    update_time timestamp
                )""");
        tableMetaCache.evict(null);

        seedRows("shr_demo_enterprise",
                "insert into shr_demo_enterprise(ent_name,credit_code,legal_person,reg_capital,industry,"
                        + "address,phone,reg_date,status) values (?,?,?,?,?,?,?,?,?)",
                List.of(
                        row("市大数据科技有限公司", "91330100MA2A1B2C3D", "张明", new BigDecimal("1000.00"),
                                "软件和信息技术服务业", "杭州市西湖区文三路100号", "0571-88881234",
                                LocalDate.of(2015, 3, 12), "在营"),
                        row("省智慧交通建设有限公司", "91330100MA2A2C3D4E", "李伟", new BigDecimal("5000.00"),
                                "交通运输业", "杭州市滨江区江南大道200号", "0571-86662345",
                                LocalDate.of(2012, 7, 1), "在营"),
                        row("市惠民医疗集团有限公司", "91330100MA2A3D4E5F", "王芳", new BigDecimal("3000.00"),
                                "卫生和社会工作", "杭州市上城区解放路50号", "0571-87001234",
                                LocalDate.of(2018, 5, 20), "在营"),
                        row("市城市建设投资发展有限公司", "91330100MA2A4E5F6G", "赵强", new BigDecimal("20000.00"),
                                "城市建设投资", "杭州市拱墅区莫干山路300号", "0571-85998877",
                                LocalDate.of(2009, 11, 8), "在营"),
                        row("省数据服务产业有限公司", "91330100MA2A5F6G7H", "陈静", new BigDecimal("800.00"),
                                "数据处理和存储服务", "杭州市余杭区文一西路998号", "0571-88665544",
                                LocalDate.of(2020, 2, 18), "在营"),
                        row("市食品安全检测有限公司", "91330100MA2A6G7H8J", "刘洋", new BigDecimal("500.00"),
                                "专业技术服务业", "杭州市钱塘区下沙路120号", "0571-86881122",
                                LocalDate.of(2016, 9, 30), "在营"),
                        row("省新能源科技发展有限公司", "91330100MA2A7H8J9K", "孙丽", new BigDecimal("6000.00"),
                                "电气机械和器材制造业", "杭州市萧山区建设一路66号", "0571-82223344",
                                LocalDate.of(2014, 4, 26), "迁出"),
                        row("市文化旅游投资有限公司", "91330100MA2A8J9K1L", "周涛", new BigDecimal("1500.00"),
                                "文化旅游业", "杭州市临安区青山湖街道", "0571-63775566",
                                LocalDate.of(2017, 12, 5), "在营")));

        seedRows("shr_demo_hrss",
                "insert into shr_demo_hrss(person_name,id_card,phone,company,insur_type,insur_month,"
                        + "base_amount,status) values (?,?,?,?,?,?,?,?)",
                List.of(
                        row("张明", "330106198503124512", "13805711234", "市大数据科技有限公司", "职工基本养老保险",
                                "2026-09", new BigDecimal("16800.00"), "正常参保"),
                        row("李伟", "330108197811205678", "13905812345", "省智慧交通建设有限公司", "职工基本养老保险",
                                "2026-09", new BigDecimal("20500.00"), "正常参保"),
                        row("王芳", "330102199002148765", "13605715678", "市惠民医疗集团有限公司", "职工基本医疗保险",
                                "2026-09", new BigDecimal("15300.00"), "正常参保"),
                        row("赵强", "330105198209309876", "13505719876", "市城市建设投资发展有限公司",
                                "职工基本养老保险", "2026-09", new BigDecimal("26000.00"), "正常参保"),
                        row("陈静", "330110199507221234", "13705723456", "省数据服务产业有限公司", "失业保险",
                                "2026-09", new BigDecimal("9800.00"), "正常参保"),
                        row("刘洋", "330104198712115432", "13805734567", "市食品安全检测有限公司", "工伤保险",
                                "2026-09", new BigDecimal("11200.00"), "正常参保"),
                        row("孙丽", "330109199103087654", "13905745678", "省新能源科技发展有限公司", "失业保险",
                                "2026-09", new BigDecimal("12800.00"), "暂停参保"),
                        row("周涛", "330112198906182345", "13605756789", "市文化旅游投资有限公司", "职工基本医疗保险",
                                "2026-09", new BigDecimal("13500.00"), "正常参保")));

        seedRows("shr_demo_credit",
                "insert into shr_demo_credit(ent_name,credit_code,credit_score,credit_level,blacklist,"
                        + "update_time) values (?,?,?,?,?,?)",
                List.of(
                        row("市大数据科技有限公司", "91330100MA2A1B2C3D", 92, "A", "否",
                                Timestamp.valueOf("2026-10-08 09:30:00")),
                        row("省智慧交通建设有限公司", "91330100MA2A2C3D4E", 88, "A", "否",
                                Timestamp.valueOf("2026-10-08 09:30:00")),
                        row("市惠民医疗集团有限公司", "91330100MA2A3D4E5F", 76, "B", "否",
                                Timestamp.valueOf("2026-10-09 10:00:00")),
                        row("市城市建设投资发展有限公司", "91330100MA2A4E5F6G", 95, "A", "否",
                                Timestamp.valueOf("2026-10-09 10:00:00")),
                        row("省数据服务产业有限公司", "91330100MA2A5F6G7H", 68, "B", "否",
                                Timestamp.valueOf("2026-10-10 14:20:00")),
                        row("市食品安全检测有限公司", "91330100MA2A6G7H8J", 55, "C", "是",
                                Timestamp.valueOf("2026-10-10 14:20:00")),
                        row("省新能源科技发展有限公司", "91330100MA2A7H8J9K", 82, "B", "否",
                                Timestamp.valueOf("2026-10-11 08:45:00")),
                        row("市文化旅游投资有限公司", "91330100MA2A8J9K1L", 90, "A", "否",
                                Timestamp.valueOf("2026-10-11 08:45:00"))));
    }

    // ==================== 共享 API ====================

    private void seedApis() {
        CatalogBind enterprise = bindCatalog(List.of("CAT-ENTERPRISE", "RES_ENTERPRISE_BASE"),
                "企业基础信息", "企业基础信息", 2);
        CatalogBind hrss = bindCatalog(List.of("CAT-HRSS", "RES_HRSS_INSURANCE"),
                "社保参保", "社保参保信息", 4);
        CatalogBind credit = bindCatalog(List.of("CAT-CREDIT", "RES_PUBLIC_CREDIT"),
                "公共信用", "公共信用信息", 3);

        createApi("ENTERPRISE_BASE_INFO", "企业基础信息查询", enterprise,
                "shr_demo_enterprise",
                "entName,creditCode,legalPerson,regCapital,industry,address,phone,regDate,status",
                "phone,legalPerson",
                params(param("entName", "string", false, "企业名称（模糊）", "科技", "ent_name", "like"),
                        param("creditCode", "string", false, "统一社会信用代码", "91330100MA2A1B2C3D", "credit_code", "eq"),
                        param("industry", "string", false, "所属行业", "软件和信息技术服务业", "industry", "like"),
                        param("status", "string", false, "经营状态", "在营", "status", "eq")),
                100, "gBizAdmin", SeedConstants.DEPT_MARKET);
        createApi("HRSS_INSURANCE_QUERY", "社保参保查询", hrss,
                "shr_demo_hrss",
                "personName,idCard,phone,company,insurType,insurMonth,baseAmount,status",
                "personName,idCard,phone",
                params(param("personName", "string", false, "姓名（模糊）", "张", "person_name", "like"),
                        param("insurMonth", "string", false, "参保月份", "2026-09", "insur_month", "eq"),
                        param("insurType", "string", false, "险种", "职工基本养老保险", "insur_type", "like")),
                50, "hrssOperator", SeedConstants.DEPT_HRSS);
        createApi("PUBLIC_CREDIT_QUERY", "公共信用信息查询", credit,
                "shr_demo_credit",
                "entName,creditCode,creditScore,creditLevel,blacklist,updateTime",
                "creditCode",
                params(param("entName", "string", false, "企业名称（模糊）", "数据", "ent_name", "like"),
                        param("creditLevel", "string", false, "信用等级", "A", "credit_level", "eq"),
                        param("minScore", "int", false, "最低信用分", "80", "credit_score", "ge")),
                80, "creditOperator", SeedConstants.DEPT_DATA_BUREAU);
    }

    private void createApi(String apiCode, String apiName, CatalogBind catalog, String table,
                           String returnFields, String maskFields, String paramsJson, int qps,
                           String owner, Long deptId) {
        ApiSaveRequest req = new ApiSaveRequest(apiCode, apiName, catalog.id(), catalog.name(),
                null, table, "GET", paramsJson, returnFields, maskFields, qps, owner, deptId,
                "演示共享服务（种子数据）", null);
        Map<String, Object> created = shareApiService.create(req);
        Long apiId = ((Number) created.get("id")).longValue();
        // 上线：生成网关路由配置并持久化
        shareApiService.online(apiId);
        log.info("种子共享服务已创建并上线：{}（id={}）", apiCode, apiId);
    }

    // ==================== 凭据 ====================

    private void seedCredentials() {
        ShareApi enterprise = requireApi("ENTERPRISE_BASE_INFO");
        ShareApi hrss = requireApi("HRSS_INSURANCE_QUERY");
        // 演示凭据 1：企业基础信息，授权密级 3 >= 数据密级 2，返回明文
        createCredential(enterprise, SeedConstants.CONSUMER_ID, "运营管理部",
                "AKDEMOENT0001AB", "SKdemoEnterpriseSecret2026AAbbCC", 3, 180,
                "演示凭据：企业基础信息查询（高授权密级，返回明文）");
        // 演示凭据 2：社保参保，授权密级 1 < 数据密级（涉敏），强制脱敏；5 天后到期用于 T-7 提醒演示
        createCredential(hrss, SeedConstants.CONSUMER_ID, "运营管理部",
                "AKDEMOHRSS0002CD", "SKdemoHrssSecret2026CCddEEffGG", 1, 5,
                "演示凭据：社保参保查询（低授权密级，动态脱敏 + 即将到期）");
    }

    private void createCredential(ShareApi api, Long consumerId, String consumerName, String appKey,
                                  String appSecret, int grantLevel, int expireDays, String remark) {
        if (credentialRepository.findByAppKeyAndDeletedFalse(appKey).isPresent()) {
            return;
        }
        ShareCredential credential = new ShareCredential();
        credential.setApiId(api.getId());
        credential.setApiCode(api.getApiCode());
        credential.setApiName(api.getApiName());
        credential.setConsumerId(consumerId);
        credential.setConsumerName(consumerName);
        credential.setAppKey(appKey);
        credential.setAppSecretHash(IdGen.sha256(appSecret));
        credential.setAppSecretPreview(appSecret.substring(0, 4) + "****"
                + appSecret.substring(appSecret.length() - 4));
        credential.setGrantLevel(grantLevel);
        credential.setQpsLimit(api.getQpsLimit());
        credential.setStatus(ShareConstants.CRED_ACTIVE);
        credential.setExpireTime(LocalDateTime.now().plusDays(expireDays));
        credential.setCallCount(0L);
        credential.setRemark(remark);
        credentialRepository.save(credential);
    }

    // ==================== 待办申请（不同节点） ====================

    private void seedPendingApplies() {
        CatalogBind credit = bindCatalog(List.of("CAT-CREDIT", "RES_PUBLIC_CREDIT"),
                "公共信用", "公共信用信息", 3);
        CatalogBind enterprise = bindCatalog(List.of("CAT-ENTERPRISE", "RES_ENTERPRISE_BASE"),
                "企业基础信息", "企业基础信息", 2);

        // 申请 A：涉敏（3 节点），已通过业务管理员，当前停在“数据提供方”节点
        ApprovalApply applyA = newApply("AP20261101093000101", credit, 3, true,
                "用于市场主体信用评价核查", "运营管理部信用管理组",
                "entName,creditCode,creditScore,creditLevel", "consumer",
                SeedConstants.CONSUMER_ID, "运营管理部");
        List<ApprovalFlowNode> nodesA = createNodes(applyA, true);
        nodesA.get(0).setStatus(ShareConstants.NODE_APPROVED);
        nodesA.get(0).setApproverId(SeedConstants.BIZ_ADMIN_ID);
        nodesA.get(0).setApproverName("bizadmin");
        nodesA.get(0).setComment("业务合理性通过，同意进入数据提供方审核");
        nodesA.get(0).setHandleTime(LocalDateTime.now().minusDays(1));
        nodeRepository.save(nodesA.get(0));
        applyA.setCurrentNodeNo(2);
        applyA.setCurrentNodeName(nodesA.get(1).getNodeName());
        applyA.setCurrentNodeRole(nodesA.get(1).getRoleCode());
        applyA.setTotalNodes(3);
        applyRepository.save(applyA);

        // 申请 B：非涉敏（2 节点），当前停在“业务管理员”节点
        ApprovalApply applyB = newApply("AP20261102093000102", enterprise, 2, false,
                "用于企业基础信息核验比对", "运营管理部登记注册处",
                "entName,creditCode,legalPerson", "consumer", SeedConstants.CONSUMER_ID, "运营管理部");
        List<ApprovalFlowNode> nodesB = createNodes(applyB, false);
        applyB.setCurrentNodeNo(1);
        applyB.setCurrentNodeName(nodesB.get(0).getNodeName());
        applyB.setCurrentNodeRole(nodesB.get(0).getRoleCode());
        applyB.setTotalNodes(2);
        applyRepository.save(applyB);

        // 申请 C：已驳回（留痕演示）
        ApprovalApply applyC = newApply("AP20261028093000103", credit, 3, true,
                "用于信用数据批量导出分析", "外部商业机构",
                "entName,creditCode,creditScore", "consumer", SeedConstants.CONSUMER_ID, "运营管理部");
        List<ApprovalFlowNode> nodesC = createNodes(applyC, true);
        nodesC.get(0).setStatus(ShareConstants.NODE_REJECTED);
        nodesC.get(0).setApproverId(SeedConstants.BIZ_ADMIN_ID);
        nodesC.get(0).setApproverName("bizadmin");
        nodesC.get(0).setComment("申请用途超出共享范围，请补充使用场景说明");
        nodesC.get(0).setHandleTime(LocalDateTime.now().minusDays(3));
        nodeRepository.save(nodesC.get(0));
        nodesC.get(1).setStatus(ShareConstants.NODE_SKIPPED);
        nodesC.get(2).setStatus(ShareConstants.NODE_SKIPPED);
        nodeRepository.saveAll(nodesC.subList(1, nodesC.size()));
        applyC.setStatus(ShareConstants.APPLY_REJECTED);
        applyC.setRejectReason("申请用途超出共享范围，请补充使用场景说明");
        applyC.setCurrentNodeName("已驳回");
        applyRepository.save(applyC);
    }

    // ==================== 已通过申请 + 授权 + 凭据 ====================

    private void seedApprovedApply() {
        CatalogBind hrss = bindCatalog(List.of("CAT-HRSS", "RES_HRSS_INSURANCE"),
                "社保参保", "社保参保信息", 4);
        ShareApi api = requireApi("HRSS_INSURANCE_QUERY");

        ApprovalApply apply = newApply("AP20261015093000104", hrss, 4, true,
                "用于社保参保情况核验（对外服务场景）", "数据中心数据服务处",
                "personName,insurType,insurMonth", "provider",
                SeedConstants.PROVIDER_ID, "人力资源部");
        List<ApprovalFlowNode> nodes = new ArrayList<>();
        nodes.add(node(apply, 1, ShareConstants.NODE_TYPE_BIZ_ADMIN, "业务管理员审批",
                ShareConstants.ROLE_BIZ_ADMIN, ShareConstants.NODE_APPROVED,
                SeedConstants.BIZ_ADMIN_ID, "bizadmin", "同意，属于跨部门共享场景"));
        nodes.add(node(apply, 2, ShareConstants.NODE_TYPE_PROVIDER, "数据提供方审批",
                ShareConstants.ROLE_PROVIDER, ShareConstants.NODE_APPROVED,
                SeedConstants.PROVIDER_ID, "provider", "提供方同意供数，按约定频率交换"));
        nodes.add(node(apply, 3, ShareConstants.NODE_TYPE_SECURITY, "安全管理员审批",
                ShareConstants.ROLE_SECURITY, ShareConstants.NODE_APPROVED,
                SeedConstants.SECURITY_ID, "security", "涉敏字段已绑定脱敏策略，同意授权"));
        nodeRepository.saveAll(nodes);

        apply.setStatus(ShareConstants.APPLY_APPROVED);
        apply.setCurrentNodeNo(3);
        apply.setCurrentNodeName("审批完成");
        apply.setTotalNodes(3);
        apply.setApproveTime(LocalDateTime.now().minusDays(10));
        applyRepository.save(apply);

        LocalDateTime start = LocalDateTime.now().minusDays(10);
        // 5 天后到期：便于演示 T-7 到期提醒（/grants/scan）与续期（/applies/{id}/renew）
        LocalDateTime end = start.plusDays(15);
        ApprovalGrant grant = new ApprovalGrant();
        grant.setGrantNo("CRDEMOGRANT0104");
        grant.setApplyId(apply.getId());
        grant.setApplyNo(apply.getApplyNo());
        grant.setApiId(api.getId());
        grant.setApiCode(api.getApiCode());
        grant.setCatalogId(apply.getCatalogId());
        grant.setCatalogName(apply.getCatalogName());
        grant.setConsumerId(apply.getApplicantId());
        grant.setConsumerName(apply.getApplicantName());
        grant.setConsumerDeptId(apply.getApplicantDeptId());
        grant.setLevel(apply.getLevel());
        grant.setStartTime(start);
        grant.setEndTime(end);
        grant.setStatus(ShareConstants.GRANT_ACTIVE);
        grant.setQpsLimit(api.getQpsLimit());
        grant = grantRepository.save(grant);

        // 审批通过自动签发的凭据（演示明文口令，方便冒烟脚本调用开放接口）
        String appKey = "AKDEMOGRANT0104EF";
        String appSecret = "SKdemoGrantSecret2026EEffGGhhII";
        if (credentialRepository.findByAppKeyAndDeletedFalse(appKey).isEmpty()) {
            ShareCredential credential = new ShareCredential();
            credential.setApiId(api.getId());
            credential.setApiCode(api.getApiCode());
            credential.setApiName(api.getApiName());
            credential.setConsumerId(apply.getApplicantId());
            credential.setConsumerName(apply.getApplicantName());
            credential.setAppKey(appKey);
            credential.setAppSecretHash(IdGen.sha256(appSecret));
            credential.setAppSecretPreview(appSecret.substring(0, 4) + "****"
                    + appSecret.substring(appSecret.length() - 4));
            credential.setGrantLevel(4);
            credential.setQpsLimit(api.getQpsLimit());
            credential.setStatus(ShareConstants.CRED_ACTIVE);
            credential.setExpireTime(end);
            credential.setCallCount(0L);
            credential.setGrantId(grant.getId());
            credential.setApplyId(apply.getId());
            credential.setRemark("审批通过自动签发（演示）");
            credential = credentialRepository.save(credential);
            grant.setCredentialId(credential.getId());
            grant = grantRepository.save(grant);
            apply.setCredentialId(credential.getId());
        }
        apply.setGrantId(grant.getId());
        applyRepository.save(apply);
        log.info("种子已通过申请与授权已创建：applyNo={} grantNo={}", apply.getApplyNo(), grant.getGrantNo());
    }

    // ==================== 调用统计（20 条） ====================

    private void seedCallStats() {
        List<ShareApi> apis = apiRepository.findByStatusAndDeletedFalseOrderByIdAsc(ShareConstants.API_ONLINE);
        if (apis.isEmpty()) {
            return;
        }
        Map<String, ShareCredential> credentialByApi = new LinkedHashMap<>();
        for (ShareCredential c : credentialRepository.findAll()) {
            if (c.getApiCode() != null) {
                credentialByApi.putIfAbsent(c.getApiCode(), c);
            }
        }
        int[] costPool = {38, 45, 52, 61, 73, 88, 96, 120, 145, 210, 260, 320, 480, 620};
        LocalDateTime now = LocalDateTime.now();
        List<ShareCallStat> stats = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ShareApi api = apis.get(i % apis.size());
            ShareCredential credential = credentialByApi.get(api.getApiCode());
            ShareCallStat stat = new ShareCallStat();
            stat.setApiId(api.getId());
            stat.setApiCode(api.getApiCode());
            stat.setApiName(api.getApiName());
            if (credential != null) {
                stat.setCredentialId(credential.getId());
                stat.setAppKey(credential.getAppKey());
                stat.setConsumerId(credential.getConsumerId());
            } else {
                stat.setAppKey(ShareConstants.CHANNEL_DEBUG);
            }
            stat.setChannel(i % 9 == 0 ? ShareConstants.CHANNEL_DEBUG : ShareConstants.CHANNEL_OPEN);
            stat.setCostMs((long) costPool[i % costPool.length]);
            stat.setStatus(i % 7 == 3 ? ShareConstants.STAT_FAIL : ShareConstants.STAT_SUCCESS);
            stat.setRowCount(i % 7 == 3 ? 0 : (i % 10) + 1);
            // 近 7 天分布
            stat.setCallTime(now.minusDays(i % 7).minusHours((i * 3L) % 12).minusMinutes(i * 7L % 60));
            stat.setIp("10.20.30." + (10 + i));
            stat.setErrorMsg(i % 7 == 3 ? "查询超时：源端响应超过 3s" : null);
            stats.add(stat);
        }
        statRepository.saveAll(stats);

        // 回填共享服务的调用计量（与统计明细保持一致）
        for (ShareApi api : apis) {
            List<ShareCallStat> own = stats.stream().filter(s -> api.getId().equals(s.getApiId())).toList();
            api.setCallCount((long) own.size());
            own.stream().map(ShareCallStat::getCallTime).max(LocalDateTime::compareTo)
                    .ifPresent(api::setLastCallTime);
            apiRepository.save(api);
        }
    }

    // ==================== 通用工具 ====================

    private ApprovalApply newApply(String applyNo, CatalogBind catalog, int level, boolean sensitive,
                                   String purpose, String scope, String itemScope, String applicantName,
                                   Long applicantId, String deptName) {
        ApprovalApply apply = new ApprovalApply();
        apply.setApplyNo(applyNo);
        apply.setCatalogId(catalog.id());
        apply.setCatalogName(catalog.name());
        apply.setApplicantId(applicantId);
        apply.setApplicantName(applicantName);
        apply.setApplicantDeptId(SeedConstants.DEPT_MARKET);
        apply.setPurpose(purpose);
        apply.setScope(scope);
        apply.setStartDate(LocalDate.now().minusDays(5));
        apply.setEndDate(LocalDate.now().plusDays(85));
        apply.setItemScope(itemScope);
        apply.setLevel(level);
        apply.setSensitive(sensitive);
        apply.setStatus(ShareConstants.APPLY_PENDING);
        apply.setCurrentNodeNo(1);
        apply.setTotalNodes(sensitive ? 3 : 2);
        apply.setRemark("种子数据（" + deptName + "）");
        return applyRepository.save(apply);
    }

    private List<ApprovalFlowNode> createNodes(ApprovalApply apply, boolean sensitive) {
        List<ApprovalFlowNode> nodes = new ArrayList<>();
        nodes.add(node(apply, 1, ShareConstants.NODE_TYPE_BIZ_ADMIN, "业务管理员审批",
                ShareConstants.ROLE_BIZ_ADMIN, ShareConstants.NODE_PENDING, null, null, null));
        nodes.add(node(apply, 2, ShareConstants.NODE_TYPE_PROVIDER, "数据提供方审批",
                ShareConstants.ROLE_PROVIDER, ShareConstants.NODE_PENDING, null, null, null));
        if (sensitive) {
            nodes.add(node(apply, 3, ShareConstants.NODE_TYPE_SECURITY, "安全管理员审批",
                    ShareConstants.ROLE_SECURITY, ShareConstants.NODE_PENDING, null, null, null));
        }
        return nodeRepository.saveAll(nodes);
    }

    private ApprovalFlowNode node(ApprovalApply apply, int no, String nodeType, String nodeName,
                                  String roleCode, String status, Long approverId, String approverName,
                                  String comment) {
        ApprovalFlowNode node = new ApprovalFlowNode();
        node.setApplyId(apply.getId());
        node.setApplyNo(apply.getApplyNo());
        node.setNodeNo(no);
        node.setNodeName(nodeName);
        node.setNodeType(nodeType);
        node.setRoleCode(roleCode);
        node.setStatus(status);
        node.setApproverId(approverId);
        node.setApproverName(approverName);
        node.setComment(comment);
        if (!ShareConstants.NODE_PENDING.equals(status)) {
            node.setHandleTime(LocalDateTime.now().minusDays(1));
        }
        return node;
    }

    /** 目录绑定：先按 resource_code 查，再按名称模糊查；都查不到则 catalogId=null 并保留名称快照。 */
    private CatalogBind bindCatalog(List<String> resourceCodes, String nameKeyword, String fallbackName,
                                    int fallbackLevel) {
        for (String code : resourceCodes) {
            var ref = crossDomainQueryService.findCatalogByCode(code);
            if (ref.isPresent()) {
                return new CatalogBind(ref.get().id(),
                        ref.get().name() == null ? fallbackName : ref.get().name(),
                        ref.get().level() == null ? fallbackLevel : ref.get().level());
            }
        }
        var byName = crossDomainQueryService.findCatalogByName(nameKeyword);
        if (byName.isPresent()) {
            return new CatalogBind(byName.get().id(),
                    byName.get().name() == null ? fallbackName : byName.get().name(),
                    byName.get().level() == null ? fallbackLevel : byName.get().level());
        }
        log.info("未在 cat_catalog 中匹配到目录资源 [{}]，共享服务使用名称快照绑定", nameKeyword);
        return new CatalogBind(null, fallbackName, fallbackLevel);
    }

    private ShareApi requireApi(String apiCode) {
        return apiRepository.findByApiCodeAndDeletedFalse(apiCode)
                .orElseThrow(() -> new IllegalStateException("种子共享服务不存在: " + apiCode));
    }

    private void execute(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.warn("演示表创建失败（可能已存在或表结构差异）: {}", e.getMessage());
        }
    }

    private void seedRows(String table, String insertSql, List<Object[]> rows) {
        try {
            Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.batchUpdate(insertSql, rows);
            log.info("演示业务表 {} 初始化 {} 行样例数据", table, rows.size());
        } catch (Exception e) {
            log.warn("演示业务表 {} 样例数据初始化失败: {}", table, e.getMessage());
        }
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static Map<String, Object> param(String name, String type, boolean required, String desc,
                                             String example, String column, String op) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("required", required);
        map.put("desc", desc);
        map.put("example", example);
        map.put("column", column);
        map.put("op", op);
        return map;
    }

    @SafeVarargs
    private static String params(Map<String, Object>... defs) {
        return JsonUtil.toJson(List.of(defs));
    }

    /** 目录绑定结果。 */
    private record CatalogBind(Long id, String name, int level) {
    }

    @SuppressWarnings("unused")
    private static Timestamp ts(String value) {
        return Timestamp.valueOf(value);
    }

    @SuppressWarnings("unused")
    private static Date date(String value) {
        return Date.valueOf(value);
    }
}

