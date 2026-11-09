package com.share.auth.support;

import com.share.auth.entity.SysUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 口令摘要工具：SHA-256(盐 + 口令)，自带随机盐，不引入额外依赖。
 * <p>演示环境口令统一 123456；生产应替换为 BCrypt/Argon2 等慢哈希。</p>
 */
public final class PasswordUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    /** 生成 32 位十六进制随机盐。 */
    public static String newSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 计算 SHA-256(盐 + 明文口令) 的十六进制摘要。 */
    public static String hash(String rawPassword, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String plain = (salt == null ? "" : salt) + (rawPassword == null ? "" : rawPassword);
            return HexFormat.of().formatHex(digest.digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("口令摘要计算失败", e);
        }
    }

    /** 校验口令。 */
    public static boolean matches(String rawPassword, String salt, String expectedHash) {
        if (rawPassword == null || expectedHash == null) {
            return false;
        }
        return expectedHash.equalsIgnoreCase(hash(rawPassword, salt));
    }

    /** 为用户写入新盐与新摘要（调用方负责持久化）。 */
    public static void applyTo(SysUser user, String rawPassword) {
        String salt = newSalt();
        user.setPasswordSalt(salt);
        user.setPasswordHash(hash(rawPassword, salt));
    }
}
