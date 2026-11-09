package com.share.common.audit;

import com.share.common.security.LoginUser;
import com.share.common.security.UserContext;
import com.share.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/** 审计切面：采集操作人/IP/耗时/结果并发布 {@link AuditEvent}。 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final ApplicationEventPublisher publisher;

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        String error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            error = t.getMessage();
            throw t;
        } finally {
            try {
                publish(pjp, auditLog, error, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("审计事件发布失败: {}", e.getMessage());
            }
        }
    }

    private void publish(ProceedingJoinPoint pjp, AuditLog auditLog, String error, long cost) {
        LoginUser user = UserContext.get();
        HttpServletRequest request = currentRequest();
        AuditEvent.AuditEventBuilder builder = AuditEvent.builder()
                .action(auditLog.action())
                .targetType(auditLog.targetType())
                .result(error == null ? "SUCCESS" : "FAIL")
                .errorMsg(truncate(error, 500))
                .costMs(cost)
                .operateTime(LocalDateTime.now())
                .traceId(com.share.common.web.TraceIdHolder.get())
                .operatorId(user == null ? null : user.getUserId())
                .operatorName(user == null ? "anonymous" : user.getUsername())
                .roleCode(user == null ? null : user.getRoleCode())
                .module(moduleOf(pjp));
        if (request != null) {
            builder.ip(clientIp(request))
                    .userAgent(truncate(request.getHeader("User-Agent"), 300))
                    .method(request.getMethod())
                    .uri(request.getRequestURI());
        }
        if (auditLog.recordArgs()) {
            try {
                builder.detail(truncate(JsonUtil.toJson(pjp.getArgs()), 2000));
            } catch (Exception ignored) {
                builder.detail("args-unserializable");
            }
        }
        publisher.publishEvent(builder.build());
    }

    private String moduleOf(ProceedingJoinPoint pjp) {
        String pkg = pjp.getSignature().getDeclaringTypeName();
        String[] parts = pkg.split("\\.");
        return parts.length > 2 ? parts[2] : "unknown";
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    public static String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v)) {
                return v.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max) + "...";
    }
}
