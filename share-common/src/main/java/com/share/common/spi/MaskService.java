package com.share.common.spi;

/**
 * 脱敏服务 SPI：由 share-security 提供实现；交换引擎（静态脱敏）与共享 API（动态脱敏）统一调用。
 * <p>参数 param 为算法参数串，如 MASK 的 "3,4"（保留前 3 后 4）、REPLACE 的 "***"。</p>
 */
public interface MaskService {

    /**
     * 对单个值脱敏。
     *
     * @param type  算法类型
     * @param value 原始值
     * @param param 算法参数
     * @return 脱敏后的值；入参为 null 时返回 null
     */
    String mask(MaskType type, String value, String param);

    /** 是否支持该算法。 */
    default boolean supports(MaskType type) {
        return true;
    }
}
