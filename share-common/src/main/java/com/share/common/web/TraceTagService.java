package com.share.common.web;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/** 批次级链路标签：写入 MDC，并在 SkyWalking Agent 存在时写入 active span tag。 */
@Slf4j
@Component
public class TraceTagService {

    public static final String BATCH_ID = "batchId";

    public void beginBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return;
        }
        MDC.put(BATCH_ID, batchId);
        tag(BATCH_ID, batchId);
    }

    public void endBatch() {
        MDC.remove(BATCH_ID);
    }

    public void tag(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        try {
            Class<?> activeSpan = Class.forName("org.apache.skywalking.apm.toolkit.trace.ActiveSpan");
            Method tag = activeSpan.getMethod("tag", String.class, String.class);
            tag.invoke(null, key, value);
        } catch (ClassNotFoundException ignore) {
            // 未挂载 SkyWalking Agent/工具包时仅保留 MDC 标签
        } catch (Throwable e) {
            log.debug("SkyWalking tag 写入失败 {}={}: {}", key, value, e.getMessage());
        }
    }
}
