package com.share.common.spi;

/** 脱敏算法类型（FR-10）。 */
public enum MaskType {
    /** 遮蔽，如 138****1234 */
    MASK,
    /** 替换为固定值 */
    REPLACE,
    /** 哈希（不可逆） */
    HASH,
    /** 泛化，如按区间/取整 */
    GENERALIZE,
    /** 加扰，可逆的随机变换 */
    PERTURB,
    /** 加密，可逆 */
    ENCRYPT,
    /** 不处理 */
    NONE
}
