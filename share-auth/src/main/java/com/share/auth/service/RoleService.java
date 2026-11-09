package com.share.auth.service;

import com.share.auth.dto.AdminRequests;
import com.share.auth.dto.VOs;
import com.share.auth.entity.SysRole;
import com.share.auth.entity.SysRolePermission;
import com.share.auth.repository.SysRolePermissionRepository;
import com.share.auth.repository.SysRoleRepository;
import com.share.auth.repository.SysUserRepository;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 角色与权限管理服务（FR-13）：角色 CRUD + 权限分配，变更后清空 PermissionProvider 缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysUserRepository userRepository;
    private final RbacPermissionProvider permissionProvider;

    /** 角色列表（含权限编码与用户数）。 */
    public List<VOs.RoleVO> list() {
        return roleRepository.findByDeletedFalseOrderBySortNoAscIdAsc().stream()
                .map(this::toVO)
                .toList();
    }

    /** 角色详情。 */
    public VOs.RoleVO get(Long id) {
        return toVO(requireRole(id));
    }

    /** 新增角色（可同时分配权限）。 */
    @Transactional
    public VOs.RoleVO create(AdminRequests.RoleSaveRequest request) {
        String roleCode = normalizeCode(request.roleCode());
        if (roleCode == null || roleCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色编码不能为空");
        }
        if (roleRepository.existsByRoleCode(roleCode)) {
            throw new BizException(ErrorCode.CONFLICT, "角色编码已存在: " + roleCode);
        }
        if (request.roleName() == null || request.roleName().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色名称不能为空");
        }
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(request.roleName().trim());
        role.setDescription(request.description());
        role.setStatus(request.status() == null ? 1 : request.status());
        role.setSortNo(request.sortNo() == null ? 99 : request.sortNo());
        role.setBuiltin(false);
        SysRole saved = roleRepository.save(role);
        if (request.permissionCodes() != null) {
            replacePermissions(roleCode, request.permissionCodes());
        }
        permissionProvider.invalidate(roleCode);
        log.info("新增角色: code={}, name={}", roleCode, saved.getRoleName());
        return toVO(saved);
    }

    /** 修改角色（角色编码不可变更，避免破坏存量用户与授权数据）。 */
    @Transactional
    public VOs.RoleVO update(Long id, AdminRequests.RoleSaveRequest request) {
        SysRole role = requireRole(id);
        if (request.roleCode() != null && !request.roleCode().isBlank()
                && !normalizeCode(request.roleCode()).equals(normalizeCode(role.getRoleCode()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色编码创建后不可修改");
        }
        if (request.roleName() != null && !request.roleName().isBlank()) {
            role.setRoleName(request.roleName().trim());
        }
        if (request.description() != null) {
            role.setDescription(request.description());
        }
        if (request.status() != null) {
            role.setStatus(request.status());
        }
        if (request.sortNo() != null) {
            role.setSortNo(request.sortNo());
        }
        roleRepository.save(role);
        if (request.permissionCodes() != null) {
            replacePermissions(role.getRoleCode(), request.permissionCodes());
        }
        permissionProvider.invalidate(role.getRoleCode());
        log.info("修改角色: id={}, code={}", id, role.getRoleCode());
        return toVO(role);
    }

    /** 删除角色（内置角色与仍有用户引用的角色不可删除）。 */
    @Transactional
    public void delete(Long id) {
        SysRole role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw new BizException(ErrorCode.CONFLICT, "内置角色不允许删除");
        }
        long userCount = userRepository.countByDeletedFalseAndRoleCode(normalizeCode(role.getRoleCode()));
        if (userCount > 0) {
            throw new BizException(ErrorCode.CONFLICT, "角色下仍有 " + userCount + " 个用户，不能删除");
        }
        rolePermissionRepository.deleteByRoleCode(role.getRoleCode());
        role.setDeleted(true);
        role.setStatus(0);
        roleRepository.save(role);
        permissionProvider.invalidate(role.getRoleCode());
        log.info("删除角色: id={}, code={}", id, role.getRoleCode());
    }

    /** 权限分配：全量覆盖角色权限集合。 */
    @Transactional
    public VOs.RoleVO assignPermissions(Long id, List<String> permissionCodes) {
        SysRole role = requireRole(id);
        replacePermissions(role.getRoleCode(), permissionCodes == null ? List.of() : permissionCodes);
        permissionProvider.invalidate(role.getRoleCode());
        log.info("角色权限分配: code={}, count={}", role.getRoleCode(),
                rolePermissionRepository.countByRoleCode(role.getRoleCode()));
        return toVO(role);
    }

    /** 覆盖写入角色权限（过滤未知权限点，避免脏数据）。 */
    private void replacePermissions(String roleCode, List<String> permissionCodes) {
        rolePermissionRepository.deleteByRoleCode(roleCode);
        Set<String> valid = new LinkedHashSet<>();
        for (String code : permissionCodes) {
            if (code != null && PermissionCatalog.codes().contains(code.trim())) {
                valid.add(code.trim());
            }
        }
        List<SysRolePermission> entities = new ArrayList<>();
        for (String code : valid) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleCode(roleCode);
            rp.setPermissionCode(code);
            entities.add(rp);
        }
        if (!entities.isEmpty()) {
            rolePermissionRepository.saveAll(entities);
        }
    }

    private VOs.RoleVO toVO(SysRole role) {
        List<String> permissionCodes = rolePermissionRepository.findByRoleCode(role.getRoleCode()).stream()
                .map(SysRolePermission::getPermissionCode)
                .sorted()
                .toList();
        long userCount = userRepository.countByDeletedFalseAndRoleCode(normalizeCode(role.getRoleCode()));
        return new VOs.RoleVO(role.getId(), normalizeCode(role.getRoleCode()), role.getRoleName(),
                role.getDescription(), role.getStatus(), role.getSortNo(), role.getBuiltin(),
                (long) permissionCodes.size(), permissionCodes, userCount);
    }

    private SysRole requireRole(Long id) {
        return roleRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "角色不存在: " + id));
    }

    private String normalizeCode(String roleCode) {
        return roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT);
    }
}
