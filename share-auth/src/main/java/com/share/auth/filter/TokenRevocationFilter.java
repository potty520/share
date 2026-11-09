package com.share.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.share.auth.service.TokenBlacklistService;
import com.share.common.api.ErrorCode;
import com.share.common.api.Result;
import com.share.common.web.TraceIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 令牌吊销校验过滤器：在 {@link com.share.common.security.AuthFilter} 之后执行，
 * 命中内存黑名单的令牌直接返回 401，使 /logout 具备真实失效语义。
 */
@Component
@Order(-80)
@RequiredArgsConstructor
public class TokenRevocationFilter extends OncePerRequestFilter {

    private final TokenBlacklistService blacklistService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && blacklistService.isRevoked(header.substring(7))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Result<Void> body = Result.fail(ErrorCode.UNAUTHORIZED.getCode(), "令牌已登出，请重新登录");
            body.setTraceId(TraceIdHolder.get());
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }
        chain.doFilter(request, response);
    }
}
