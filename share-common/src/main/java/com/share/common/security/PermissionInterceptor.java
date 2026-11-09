package com.share.common.security;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;

/** 接口级权限校验拦截器，配合 {@link RequirePermission} 使用。 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final ObjectProvider<PermissionProvider> permissionProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (required == null) {
            return true;
        }
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (user.isAdmin()) {
            return true;
        }
        PermissionProvider provider = permissionProvider.getIfAvailable();
        if (provider == null) {
            // 权限模块未装配时降级为“登录即可访问”，避免阻塞其它域联调
            return true;
        }
        Set<String> granted = provider.permissionsOf(user.getRoleCode());
        boolean ok = Arrays.stream(required.value()).allMatch(granted::contains);
        if (!ok) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "缺少权限 " + Arrays.toString(required.value()));
        }
        return true;
    }
}
