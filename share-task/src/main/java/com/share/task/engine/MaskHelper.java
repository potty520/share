package com.share.task.engine;

import com.share.common.spi.DefaultMaskService;
import com.share.common.spi.MaskService;
import com.share.common.spi.MaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 静态脱敏入口：转发到 share-security 提供的 {@link MaskService} 实现（@Primary），
 * 安全域尚未装配时降级为恒等变换，保证交换链路不中断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaskHelper {

    private final ObjectProvider<MaskService> provider;

    private volatile MaskService resolved;

    /**
     * 对单个字段值脱敏。
     *
     * @param type  算法类型（MASK/REPLACE/HASH/GENERALIZE/PERTURB/ENCRYPT），空或 NONE 原样返回
     * @param value 原值
     * @param param 算法参数
     */
    public String mask(String type, String value, String param) {
        if (value == null || type == null || type.isBlank() || "NONE".equalsIgnoreCase(type.trim())) {
            return value;
        }
        MaskType maskType;
        try {
            maskType = MaskType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知脱敏算法 {}，跳过脱敏", type);
            return value;
        }
        MaskService service = resolve();
        try {
            return service.mask(maskType, value, param);
        } catch (Exception e) {
            log.warn("脱敏失败 type={} msg={}", type, e.getMessage());
            return value;
        }
    }

    /** 解析一次并缓存脱敏服务实现。 */
    private MaskService resolve() {
        MaskService local = resolved;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (resolved == null) {
                MaskService candidate;
                try {
                    candidate = provider.getIfAvailable();
                } catch (Exception e) {
                    candidate = provider.orderedStream().findFirst().orElse(null);
                }
                resolved = candidate == null ? new DefaultMaskService() : candidate;
            }
            return resolved;
        }
    }
}
