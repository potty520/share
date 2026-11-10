package com.share.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.share.common.api.ErrorCode;
import com.share.common.api.Result;
import com.share.common.web.TraceIdHolder;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 认证过滤器：解析 Authorization: Bearer &lt;JWT&gt;，写入 {@link UserContext}。
 * <p>白名单：登录/刷新/验证码、开放 API（/api/open，走 AppKey）、Agent 注册、健康检查、OpenAPI 文档。</p>
 */
@Slf4j
@Component
@Order(-90)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    public static final List<String> WHITELIST = List.of(
            "/api/auth/v1/login",
            "/api/auth/v1/refresh",
            "/api/auth/v1/captcha",
            "/api/auth/v1/mfa/verify",
            "/api/agent/v1/register",
            "/api/health",
            "/api/mtls/",
            "/api/open/",
            "/actuator",
            "/v3/api-docs",
            "/swagger-ui",
            "/error"
    );

    private final JwtTokenUtil jwtTokenUtil;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            return true;
        }
        return WHITELIST.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "缺少访问令牌");
            return;
        }
        try {
            LoginUser user = jwtTokenUtil.parse(header.substring(7).trim());
            if ("refresh".equals(user.getTokenType())) {
                reject(response, "refresh token 不能用于访问业务接口");
                return;
            }
            UserContext.set(user);
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("令牌校验失败: {}", e.getMessage());
            reject(response, "令牌无效或已过期");
        } finally {
            UserContext.clear();
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.fail(ErrorCode.UNAUTHORIZED.getCode(), message);
        body.setTraceId(TraceIdHolder.get());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
