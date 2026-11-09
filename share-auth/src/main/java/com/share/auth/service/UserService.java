package com.share.auth.service;

import com.share.auth.dto.AdminRequests;
import com.share.auth.dto.VOs;
import com.share.auth.entity.SysDept;
import com.share.auth.entity.SysRole;
import com.share.auth.entity.SysUser;
import com.share.auth.repository.SysDeptRepository;
import com.share.auth.repository.SysRoleRepository;
import com.share.auth.repository.SysUserRepository;
import com.share.auth.support.PasswordUtil;
import com.share.common.api.ErrorCode;
import com.share.common.constant.SeedConstants;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理服务（FR-13）：分页查询、增删改、启停、重置口令。
 * <p>口令统一加盐摘要存储；删除采用逻辑删除，保留审计轨迹。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepository;
    private final SysDeptRepository deptRepository;
    private final SysRoleRepository roleRepository;

    /** 分页查询用户：关键字（用户名/姓名/手机号）、部门、角色、状态过滤。 */
    public VOs.PageResult<VOs.UserVO> list(String keyword, Long deptId, String roleCode, Integer status,
                                           int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Specification<SysUser> spec = buildSpec(keyword, deptId, roleCode, status);
        Page<SysUser> result = userRepository.findAll(spec,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, String> deptNames = deptNameMap();
        Map<String, String> roleNames = roleNameMap();
        List<VOs.UserVO> list = result.getContent().stream()
                .map(u -> toVO(u, deptNames, roleNames))
                .toList();
        return new VOs.PageResult<>(list, result.getTotalElements());
    }

    /** 用户详情。 */
    public VOs.UserVO get(Long id) {
        SysUser user = requireUser(id);
        return toVO(user, deptNameMap(), roleNameMap());
    }

    /** 新增用户：用户名唯一，默认演示口令 123456，角色/部门必须存在。 */
    @Transactional
    public VOs.UserVO create(AdminRequests.UserSaveRequest request) {
        String username = trim(request.username());
        if (username == null || username.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在: " + username);
        }
        String roleCode = AuthService.normalizeRole(request.roleCode());
        if (roleCode == null || roleCode.isBlank()) {
            roleCode = SeedConstants.ROLE_CONSUMER;
        }
        validateRole(roleCode);
        validateDept(request.deptId());

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(request.realName());
        user.setPhone(request.phone());
        user.setEmail(request.email());
        user.setDeptId(request.deptId());
        user.setRoleCode(roleCode);
        user.setStatus(request.status() == null ? 1 : request.status());
        user.setMfaEnabled(Boolean.TRUE.equals(request.mfaEnabled()));
        user.setRemark(request.remark());
        user.setLoginFailCount(0);
        String rawPassword = request.password();
        if (rawPassword == null || rawPassword.isBlank()) {
            rawPassword = SeedConstants.DEMO_PASSWORD;
        } else if (rawPassword.length() < 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "初始口令长度不能小于 6 位");
        }
        PasswordUtil.applyTo(user, rawPassword);
        SysUser saved = userRepository.save(user);
        log.info("新增用户: id={}, username={}, role={}", saved.getId(), username, roleCode);
        return toVO(saved, deptNameMap(), roleNameMap());
    }

    /** 修改用户基础信息（用户名可改，角色/部门校验存在性；不修改口令）。 */
    @Transactional
    public VOs.UserVO update(Long id, AdminRequests.UserSaveRequest request) {
        SysUser user = requireUser(id);
        if (request.username() != null && !request.username().isBlank()
                && !request.username().trim().equals(user.getUsername())) {
            String newUsername = request.username().trim();
            if (userRepository.existsByUsernameAndIdNot(newUsername, id)) {
                throw new BizException(ErrorCode.CONFLICT, "用户名已存在: " + newUsername);
            }
            user.setUsername(newUsername);
        }
        if (request.realName() != null) {
            user.setRealName(request.realName());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.deptId() != null) {
            validateDept(request.deptId());
            user.setDeptId(request.deptId());
        }
        if (request.roleCode() != null && !request.roleCode().isBlank()) {
            String roleCode = AuthService.normalizeRole(request.roleCode());
            validateRole(roleCode);
            user.setRoleCode(roleCode);
        }
        if (request.status() != null) {
            validateStatus(request.status());
            user.setStatus(request.status());
        }
        if (request.mfaEnabled() != null) {
            user.setMfaEnabled(request.mfaEnabled());
        }
        if (request.remark() != null) {
            user.setRemark(request.remark());
        }
        SysUser saved = userRepository.save(user);
        log.info("修改用户: id={}, username={}", id, saved.getUsername());
        return toVO(saved, deptNameMap(), roleNameMap());
    }

    /** 逻辑删除用户（内置管理员与当前登录人不可删除）。 */
    @Transactional
    public void delete(Long id) {
        SysUser user = requireUser(id);
        if (Long.valueOf(SeedConstants.ADMIN_ID).equals(id)) {
            throw new BizException(ErrorCode.CONFLICT, "内置管理员账号不允许删除");
        }
        if (id.equals(UserContext.userId())) {
            throw new BizException(ErrorCode.CONFLICT, "不能删除当前登录账号");
        }
        user.setDeleted(true);
        user.setStatus(0);
        userRepository.save(user);
        log.info("删除用户: id={}, username={}", id, user.getUsername());
    }

    /** 启用 / 停用。 */
    @Transactional
    public VOs.UserVO updateStatus(Long id, Integer status) {
        validateStatus(status);
        SysUser user = requireUser(id);
        user.setStatus(status);
        SysUser saved = userRepository.save(user);
        return toVO(saved, deptNameMap(), roleNameMap());
    }

    /** 重置口令（不传则重置为演示口令 123456）。 */
    @Transactional
    public void resetPassword(Long id, String newPassword) {
        SysUser user = requireUser(id);
        String raw = (newPassword == null || newPassword.isBlank()) ? SeedConstants.DEMO_PASSWORD : newPassword;
        if (raw.length() < 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "口令长度不能小于 6 位");
        }
        PasswordUtil.applyTo(user, raw);
        user.setLoginFailCount(0);
        userRepository.save(user);
        log.info("重置用户口令: id={}, username={}", id, user.getUsername());
    }

    private SysUser requireUser(Long id) {
        return userRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + id));
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 只能为 0 或 1");
        }
    }

    private void validateRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色编码不能为空");
        }
        if (roleRepository.findByRoleCodeAndDeletedFalse(roleCode).isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色不存在: " + roleCode);
        }
    }

    private void validateDept(Long deptId) {
        if (deptId != null && deptRepository.findByIdAndDeletedFalse(deptId).isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门不存在: " + deptId);
        }
    }

    /** 动态查询条件：全部过滤项均可选。 */
    private Specification<SysUser> buildSpec(String keyword, Long deptId, String roleCode, Integer status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("deleted"), false));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("username")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("realName"), "")), like),
                        cb.like(cb.coalesce(root.<String>get("phone"), ""), like)
                ));
            }
            if (deptId != null) {
                predicates.add(cb.equal(root.get("deptId"), deptId));
            }
            if (roleCode != null && !roleCode.isBlank()) {
                predicates.add(cb.equal(root.get("roleCode"), AuthService.normalizeRole(roleCode)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Map<Long, String> deptNameMap() {
        return deptRepository.findByDeletedFalseOrderBySortNoAscIdAsc().stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName, (a, b) -> a, HashMap::new));
    }

    private Map<String, String> roleNameMap() {
        return roleRepository.findByDeletedFalseOrderBySortNoAscIdAsc().stream()
                .collect(Collectors.toMap(r -> AuthService.normalizeRole(r.getRoleCode()),
                        SysRole::getRoleName, (a, b) -> a, HashMap::new));
    }

    private VOs.UserVO toVO(SysUser u, Map<Long, String> deptNames, Map<String, String> roleNames) {
        String roleCode = AuthService.normalizeRole(u.getRoleCode());
        Function<Long, String> deptName = id -> id == null ? null : deptNames.get(id);
        return new VOs.UserVO(u.getId(), u.getUsername(), u.getRealName(), u.getPhone(), u.getEmail(),
                u.getDeptId(), deptName.apply(u.getDeptId()), roleCode, roleNames.get(roleCode),
                u.getStatus(), u.getMfaEnabled(), u.getLastLoginTime(), u.getRemark(), u.getCreateTime());
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
