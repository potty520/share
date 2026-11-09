package com.share.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 加解密工具（FR-10）：AES-256-GCM 用于数据源口令等敏感配置的落盘加密；
 * 摘要工具用于文件与数据一致性校验。
 * <p>生产环境应替换为 KMS 托管的数据密钥，密钥不落业务库。</p>
 */
public final class CryptoUtil {

    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    /** 从任意长度口令派生 32 字节 AES 密钥。 */
    public static SecretKeySpec deriveKey(String password) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] key = sha256.digest(("share-exchange:" + password).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }

    /** AES-GCM 加密，输出 Base64(iv + ciphertext)。 */
    public static String encrypt(String plain, SecretKeySpec key) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** AES-GCM 解密，输入 Base64(iv + ciphertext)。 */
    public static String decrypt(String cipherTextBase64, SecretKeySpec key) {
        if (cipherTextBase64 == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherTextBase64);
            byte[] iv = new byte[GCM_IV_LEN];
            System.arraycopy(all, 0, iv, 0, GCM_IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(all, GCM_IV_LEN, all.length - GCM_IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    public static String md5(byte[] data) {
        return digest("MD5", data);
    }

    public static String sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败", e);
        }
    }
}
