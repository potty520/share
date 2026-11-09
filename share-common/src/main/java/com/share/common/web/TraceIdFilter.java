package com.share.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 为每个请求注入 traceId（响应头 X-Trace-Id 便于问题定位）。 */
@Component
@Order(-100)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TraceIdHolder.HEADER);
        String traceId = (incoming == null || incoming.isBlank()) ? TraceIdHolder.getOrCreate() : incoming;
        TraceIdHolder.set(traceId);
        response.setHeader(TraceIdHolder.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
