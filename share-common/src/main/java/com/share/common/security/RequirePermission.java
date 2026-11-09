package com.share.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限声明，例如：
 * <pre>@RequirePermission(PermissionConstants.CATALOG_WRITE)</pre>
 * ADMIN 角色自动放行；其余角色按授权集合校验。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {

    /** 需要的权限标识，多个时表示“同时具备”。 */
    String[] value();
}
