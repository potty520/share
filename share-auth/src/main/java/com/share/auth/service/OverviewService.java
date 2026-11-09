package com.share.auth.service;

import com.share.auth.dto.VOs;
import com.share.auth.entity.SysDept;
import com.share.auth.entity.SysRole;
import com.share.auth.entity.SysUser;
import com.share.auth.repository.SysDeptRepository;
import com.share.auth.repository.SysRoleRepository;
import com.share.auth.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户与角色概览（工作台统计）：用户总数/启用数/停用数/MFA 数、角色与部门分布、最近登录。
 */
@Service
@RequiredArgsConstructor
public class OverviewService {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysDeptRepository deptRepository;

    public VOs.Overview overview() {
        long userTotal = userRepository.countByDeletedFalse();
        long active = userRepository.countByDeletedFalseAndStatus(1);
        long disabled = userRepository.countByDeletedFalseAndStatus(0);
        long mfa = userRepository.countByDeletedFalseAndMfaEnabledTrue();
        long roleTotal = roleRepository.countByDeletedFalse();
        long deptTotal = deptRepository.countByDeletedFalse();

        List<SysRole> roles = roleRepository.findByDeletedFalseOrderBySortNoAscIdAsc();
        List<VOs.RoleStat> usersByRole = roles.stream()
                .map(r -> new VOs.RoleStat(AuthService.normalizeRole(r.getRoleCode()), r.getRoleName(),
                        userRepository.countByDeletedFalseAndRoleCode(AuthService.normalizeRole(r.getRoleCode()))))
                .toList();

        List<SysDept> depts = deptRepository.findByDeletedFalseOrderBySortNoAscIdAsc();
        List<VOs.DeptStat> usersByDept = depts.stream()
                .map(d -> new VOs.DeptStat(d.getId(), d.getDeptName(),
                        userRepository.countByDeletedFalseAndDeptId(d.getId())))
                .toList();

        Map<Long, String> deptNames = depts.stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName, (a, b) -> a, HashMap::new));
        Map<String, String> roleNames = roles.stream()
                .collect(Collectors.toMap(r -> AuthService.normalizeRole(r.getRoleCode()),
                        SysRole::getRoleName, (a, b) -> a, HashMap::new));
        List<VOs.UserVO> recentLogins = userRepository.findTop10ByDeletedFalseOrderByLastLoginTimeDesc().stream()
                .map(u -> toVO(u, deptNames, roleNames))
                .toList();

        return new VOs.Overview(userTotal, active, disabled, mfa, roleTotal, deptTotal,
                usersByRole, usersByDept, recentLogins);
    }

    private VOs.UserVO toVO(SysUser u, Map<Long, String> deptNames, Map<String, String> roleNames) {
        String roleCode = AuthService.normalizeRole(u.getRoleCode());
        return new VOs.UserVO(u.getId(), u.getUsername(), u.getRealName(), u.getPhone(), u.getEmail(),
                u.getDeptId(), u.getDeptId() == null ? null : deptNames.get(u.getDeptId()),
                roleCode, roleNames.get(roleCode), u.getStatus(), u.getMfaEnabled(),
                u.getLastLoginTime(), u.getRemark(), u.getCreateTime());
    }
}
