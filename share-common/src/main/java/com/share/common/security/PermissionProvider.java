package com.share.common.security;

import java.util.Set;

/**
 * 权限来源 SPI：由 share-auth 实现（数据库 RBAC），share-common 只依赖接口。
 */
public interface PermissionProvider {

    /** 某角色拥有的权限标识集合。 */
    Set<String> permissionsOf(String roleCode);
}
