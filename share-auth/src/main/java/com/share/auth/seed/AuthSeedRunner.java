package com.share.auth.seed;

import com.share.auth.entity.SysDept;
import com.share.auth.entity.SysRole;
import com.share.auth.entity.SysRolePermission;
import com.share.auth.entity.SysUser;
import com.share.auth.repository.SysDeptRepository;
import com.share.auth.repository.SysRolePermissionRepository;
import com.share.auth.repository.SysRoleRepository;
import com.share.auth.repository.SysUserRepository;
import com.share.auth.support.PasswordUtil;
import com.share.common.constant.SeedConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证域种子数据（FR-13）：5 个部门、6 个角色、全部权限点、6 个演示账号 + 4 个普通用户。
 * <p>部门 id 与 {@link SeedConstants} 对齐（大数据局 1 / 税务局 2 / 市监局 3 / 人社局 4 / 平台运维部 9），
 * 采用原生 INSERT 固定主键后再重置自增序列，保证演示数据跨域引用一致。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AuthSeedRunner implements CommandLineRunner {

    private final SysDeptRepository deptRepository;
    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysUserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("share.seed.enabled=false，跳过认证域种子数据");
            return;
        }
        try {
            seedDepts();
            seedRoles();
            seedUsers();
            log.info("认证域种子数据就绪：部门 {} 个、角色 {} 个、用户 {} 个",
                    deptRepository.count(), roleRepository.count(), userRepository.count());
        } catch (Exception e) {
            // 种子失败不阻断平台启动，但必须留下明确日志
            log.error("认证域种子数据初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 5 个部门：使用显式主键保证 id 与契约一致（1/2/3/4/9），随后把自增序列重置到 10。
     */
    private void seedDepts() {
        if (deptRepository.count() > 0) {
            return;
        }
        String sql = "insert into sys_dept (id, dept_name, dept_code, parent_id, sort_no, leader, phone, email,"
                + " status, description, deleted, create_time, update_time, create_by, update_by)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        LocalDateTime now = LocalDateTime.now();
        Object[][] rows = new Object[][]{
                {1L, "市大数据局", "DEPT-DATA-BUREAU", 0L, 1, "周建国", "0571-88001001", "data@share.gov.cn", 1, "平台主管单位"},
                {2L, "市税务局", "DEPT-TAX", 1L, 2, "李海峰", "0571-88002002", "tax@share.gov.cn", 1, "税务数据提供方"},
                {3L, "市市场监管局", "DEPT-MARKET", 1L, 3, "王丽", "0571-88003003", "market@share.gov.cn", 1, "市场监管数据提供方"},
                {4L, "市人社局", "DEPT-HRSS", 1L, 4, "赵晓东", "0571-88004004", "hrss@share.gov.cn", 1, "人社数据提供方"},
                {9L, "平台运维部", "DEPT-PLATFORM", 1L, 9, "陈磊", "0571-88009009", "ops@share.gov.cn", 1, "平台运维与技术支撑"}
        };
        try {
            for (Object[] row : rows) {
                jdbcTemplate.update(sql, row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7],
                        row[8], row[9], false, now, now, "seed", "seed");
            }
            // 显式主键后重置自增序列，避免后续新增部门主键冲突
            jdbcTemplate.execute("alter table sys_dept alter column id restart with 10");
            log.info("初始化部门完成（固定主键 1/2/3/4/9）");
        } catch (Exception e) {
            log.warn("部门显式主键初始化失败，降级为自增写入: {}", e.getMessage());
            if (deptRepository.count() == 0) {
                List<SysDept> fallback = new ArrayList<>();
                for (Object[] row : rows) {
                    SysDept dept = new SysDept();
                    dept.setDeptName(String.valueOf(row[1]));
                    dept.setDeptCode(String.valueOf(row[2]));
                    dept.setParentId((Long) row[3]);
                    dept.setSortNo((Integer) row[4]);
                    dept.setLeader(String.valueOf(row[5]));
                    dept.setPhone(String.valueOf(row[6]));
                    dept.setEmail(String.valueOf(row[7]));
                    dept.setStatus((Integer) row[8]);
                    dept.setDescription(String.valueOf(row[9]));
                    fallback.add(dept);
                }
                deptRepository.saveAll(fallback);
            }
        }
    }

    /** 6 个内置角色 + 角色权限矩阵（SeedConstants.ROLE_PERMISSIONS）。 */
    private void seedRoles() {
        if (roleRepository.count() > 0) {
            return;
        }
        Map<String, String> roleNames = new LinkedHashMap<>();
        roleNames.put(SeedConstants.ROLE_ADMIN, "系统管理员");
        roleNames.put(SeedConstants.ROLE_PROVIDER, "数据提供方");
        roleNames.put(SeedConstants.ROLE_CONSUMER, "数据使用方");
        roleNames.put(SeedConstants.ROLE_SECURITY, "安全管理员");
        roleNames.put(SeedConstants.ROLE_OPS, "交换运维");
        roleNames.put(SeedConstants.ROLE_BIZ_ADMIN, "业务管理员");

        int sort = 1;
        List<SysRole> roles = new ArrayList<>();
        for (Map.Entry<String, String> entry : roleNames.entrySet()) {
            SysRole role = new SysRole();
            role.setRoleCode(entry.getKey());
            role.setRoleName(entry.getValue());
            role.setDescription("内置角色：" + entry.getValue());
            role.setStatus(1);
            role.setSortNo(sort++);
            role.setBuiltin(true);
            roles.add(role);
        }
        roleRepository.saveAll(roles);

        List<SysRolePermission> permissions = new ArrayList<>();
        for (SysRole role : roles) {
            String[] codes = SeedConstants.ROLE_PERMISSIONS.get(role.getRoleCode());
            if (codes == null) {
                continue;
            }
            for (String code : codes) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleCode(role.getRoleCode());
                rp.setPermissionCode(code);
                permissions.add(rp);
            }
        }
        rolePermissionRepository.saveAll(permissions);
        log.info("初始化角色 {} 个、权限关联 {} 条", roles.size(), permissions.size());
    }

    /** 6 个演示账号（口令 123456，admin/security 需短信码）+ 4 个普通用户。 */
    private void seedUsers() {
        if (userRepository.count() > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 顺序即主键：admin=1、provider=2、consumer=3、security=4、ops=5、bizadmin=6
        userRepository.save(buildUser("admin", "系统管理员", 1L, SeedConstants.ROLE_ADMIN, true,
                "13800000001", "admin@share.gov.cn", now.minusHours(2), "平台内置超级管理员"));
        userRepository.save(buildUser("provider", "市税务局数据专员", 2L, SeedConstants.ROLE_PROVIDER, false,
                "13800000002", "provider@share.gov.cn", now.minusHours(5), "演示：数据提供方"));
        userRepository.save(buildUser("consumer", "市市场监管局数据专员", 3L, SeedConstants.ROLE_CONSUMER, false,
                "13800000003", "consumer@share.gov.cn", now.minusHours(8), "演示：数据使用方"));
        userRepository.save(buildUser("security", "安全管理员", 1L, SeedConstants.ROLE_SECURITY, true,
                "13800000004", "security@share.gov.cn", now.minusHours(3), "演示：安全管理员，双因素登录"));
        userRepository.save(buildUser("ops", "平台交换运维员", 9L, SeedConstants.ROLE_OPS, false,
                "13800000005", "ops@share.gov.cn", now.minusHours(6), "演示：交换运维"));
        userRepository.save(buildUser("bizadmin", "业务管理员", 1L, SeedConstants.ROLE_BIZ_ADMIN, false,
                "13800000006", "bizadmin@share.gov.cn", now.minusHours(9), "演示：业务管理员"));
        // 4 个普通用户
        userRepository.save(buildUser("zhangwei", "张伟", 2L, SeedConstants.ROLE_CONSUMER, false,
                "13900000007", "zhangwei@share.gov.cn", now.minusDays(1), "税务局业务人员"));
        userRepository.save(buildUser("lina", "李娜", 3L, SeedConstants.ROLE_PROVIDER, false,
                "13900000008", "lina@share.gov.cn", now.minusDays(2), "市监局业务人员"));
        userRepository.save(buildUser("wangqiang", "王强", 4L, SeedConstants.ROLE_CONSUMER, false,
                "13900000009", "wangqiang@share.gov.cn", now.minusDays(3), "人社局业务人员"));
        userRepository.save(buildUser("zhaomin", "赵敏", 2L, SeedConstants.ROLE_OPS, false,
                "13900000010", "zhaomin@share.gov.cn", now.minusDays(4), "税务局运维人员"));
        log.info("初始化演示账号 6 个 + 普通用户 4 个，初始口令 {}", SeedConstants.DEMO_PASSWORD);
    }

    private SysUser buildUser(String username, String realName, Long deptId, String roleCode, boolean mfa,
                              String phone, String email, LocalDateTime lastLoginTime, String remark) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setDeptId(deptId);
        user.setRoleCode(roleCode);
        user.setStatus(1);
        user.setMfaEnabled(mfa);
        user.setPhone(phone);
        user.setEmail(email);
        user.setLastLoginTime(lastLoginTime);
        user.setLoginFailCount(0);
        user.setRemark(remark);
        PasswordUtil.applyTo(user, SeedConstants.DEMO_PASSWORD);
        return user;
    }
}
