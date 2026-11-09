package com.share.ops.audit.service;

import com.share.common.audit.AuditEvent;
import com.share.ops.audit.entity.AudOperationLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 审计事件消费者（FR-11）：监听各业务域发布的 {@link AuditEvent} 并落库。
 * <p>异常一律吞掉，绝不让审计入库失败反向影响业务操作。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventRecorder {

    private final AuditService auditService;

    @EventListener
    public void onAuditEvent(AuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            AudOperationLog saved = auditService.record(event);
            log.debug("审计入库 id={} action={} module={} result={}",
                    saved.getId(), saved.getAction(), saved.getModule(), saved.getResult());
        } catch (Exception e) {
            log.warn("审计事件入库失败 action={}: {}", event.getAction(), e.getMessage());
        }
    }
}
