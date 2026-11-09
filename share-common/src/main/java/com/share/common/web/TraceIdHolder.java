package com.share.common.web;

import org.slf4j.MDC;

import java.util.UUID;

/** 链路追踪 ID 持有器（请求级）。 */
public final class TraceIdHolder {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIdHolder() {
    }

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    public static String getOrCreate() {
        String tid = MDC.get(MDC_KEY);
        if (tid == null || tid.isEmpty()) {
            tid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(MDC_KEY, tid);
        }
        return tid;
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
