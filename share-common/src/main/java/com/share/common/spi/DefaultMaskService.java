package com.share.common.spi;

import org.springframework.stereotype.Component;

/**
 * 脱敏能力默认实现（恒等变换），仅作为安全域未装配时的兜底。
 * share-security 的实现标注了 @Primary，装配后优先使用。
 */
@Component
public class DefaultMaskService implements MaskService {

    @Override
    public String mask(MaskType type, String value, String param) {
        return value;
    }

    @Override
    public boolean supports(MaskType type) {
        return false;
    }
}
