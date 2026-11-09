package com.share.common.util;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 标识生成：批次号 / 追踪号 / 校验值。 */
public final class IdGen {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private IdGen() {
    }

    /** 交换批次号：SW + 时间戳 + 4 位序号，如 SW2026110914300112300042。 */
    public static String batchId() {
        return "SW" + LocalDateTime.now().format(TS) + String.format("%04d", SEQ.getAndIncrement() & 0x7FFF);
    }

    /** 申请单号：AP + 时间戳 + 3 位随机数。 */
    public static String applyNo() {
        return "AP" + LocalDateTime.now().format(TS) + String.format("%03d", RANDOM.nextInt(1000));
    }

    /** 授权/凭据编号。 */
    public static String credentialNo() {
        return "CR" + LocalDateTime.now().format(TS) + String.format("%03d", RANDOM.nextInt(1000));
    }

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 生成 AppSecret / API Key 等随机串。 */
    public static String secret(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** MD5（文件/数据一致性校验）。 */
    public static String md5(String content) {
        return digest("MD5", content);
    }

    /** SHA-256（文件完整性校验）。 */
    public static String sha256(String content) {
        return digest("SHA-256", content);
    }

    private static String digest(String algorithm, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败: " + e.getMessage(), e);
        }
    }
}
