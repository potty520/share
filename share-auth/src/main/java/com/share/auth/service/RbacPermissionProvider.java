package com.share.auth.service;

import com.share.auth.entity.SysRole;
import com.share.auth.entity.SysRolePermission;
import com.share.auth.repository.SysRolePermissionRepository;
import com.share.auth.repository.SysRoleRepository;
import com.share.common.security.PermissionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RBAC 权限提供者（FR-13）：按角色编码从 sys_role_permission 读取权限集合，带 60 秒本地缓存。
 * <p>角色权限变更时由 {@link RoleService} 主动清理缓存，保证权限实时生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacPermissionProvider implements PermissionProvider {

    private static final long CACHE_TTL_MILLIS = 60_000L;

    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Set<String> permissions, long expireAt) {
    }

    @Override
    public Set<String> permissionsOf(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Set.of();
        }
        String key = roleCode.trim().toUpperCase();
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expireAt() > now) {
            return entry.permissions();
        }
        Set<String> permissions = loadFromDb(key);
        cache.put(key, new CacheEntry(permissions, now + CACHE_TTL_MILLIS));
        return permissions;
    }

    /** 从数据库加载角色权限（停用/不存在的角色返回空集合）。 */
    private Set<String> loadFromDb(String roleCode) {
        SysRole role = roleRepository.findByRoleCodeAndDeletedFalse(roleCode).orElse(null);
        if (role == null) {
            log.debug("角色不存在，权限为空: {}", roleCode);
            return Set.of();
        }
        if (role.getStatus() != null && role.getStatus() == 0) {
            log.debug("角色已停用，权限为空: {}", roleCode);
            return Set.of();
        }
        Set<String> permissions = new TreeSet<>();
        for (SysRolePermission rp : rolePermissionRepository.findByRoleCode(roleCode)) {
            if (rp.getPermissionCode() != null && !rp.getPermissionCode().isBlank()) {
                permissions.add(rp.getPermissionCode().trim());
            }
        }
        return Set.copyOf(permissions);
    }

    /** 清理单个角色的权限缓存。 */
    public void invalidate(String roleCode) {
        if (roleCode != null) {
            cache.remove(roleCode.trim().toUpperCase());
        }
    }

    /** 清理全部权限缓存。 */
    public void invalidateAll() {
        cache.clear();
    }

    /** 当前缓存条目数（诊断用）。 */
    public int cacheSize() {
        return cache.size();
    }
}
