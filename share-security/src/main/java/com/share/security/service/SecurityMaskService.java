package com.share.security.service;

import com.share.common.spi.MaskService;
import com.share.common.spi.MaskType;
import com.share.common.util.CryptoUtil;
import com.share.security.entity.SecMaskRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脱敏引擎实现（FR-10）：覆盖 share-common 的兜底实现（@Primary）。
 * <p>支持 MASK（保留前 m 后 n）、REPLACE、HASH（加盐 SHA-256）、GENERALIZE（数值取整/日期截月/地址截区）、
 * PERTURB（按密钥流逐位可逆加扰）、ENCRYPT（AES-256-GCM）。参数解析健壮，任何异常降级返回原值，避免阻断交换链路。</p>
 */
@Slf4j
@Primary
@Service
public class SecurityMaskService implements MaskService {

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy年M月d日")
    );
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss")
    );
    /** 密级名称，用于概览与标签补全 */
    public static final String[] LEVEL_NAMES = {"公开", "内部", "敏感", "核心"};

    private final String cryptoKey;
    private final MaskLogService maskLogService;
    /** 敏感算法日志采样计数器（每 20 次记 1 条，避免高频脱敏打爆日志表） */
    private final AtomicInteger sampleCounter = new AtomicInteger();

    public SecurityMaskService(@Value("${share.crypto.key:share-exchange-local-crypto-key-change-me}") String cryptoKey,
                               MaskLogService maskLogService) {
        this.cryptoKey = cryptoKey;
        this.maskLogService = maskLogService;
    }

    @Override
    public String mask(MaskType type, String value, String param) {
        String masked = doMask(type, value, param);
        recordSensitiveSample(type, masked);
        return masked;
    }

    @Override
    public boolean supports(MaskType type) {
        return type != null;
    }

    /** 按规则脱敏并记录 sec_mask_log（规则试算、动态脱敏等敏感场景使用）。 */
    public String maskWithRule(SecMaskRule rule, String value, String sourceType, String targetType, Long targetId) {
        if (rule == null) {
            return doMask(MaskType.NONE, value, null);
        }
        MaskType type = parseType(rule.getMaskType());
        long start = System.currentTimeMillis();
        String masked = doMask(type, value, rule.getParam());
        maskLogService.record(rule.getRuleCode(), type, rule.getTargetField(), value, masked,
                sourceType, targetType, targetId, System.currentTimeMillis() - start);
        return masked;
    }

    /** 解析算法类型，非法值降级为 NONE。 */
    public static MaskType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return MaskType.NONE;
        }
        try {
            return MaskType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MaskType.NONE;
        }
    }

    /** 核心脱敏逻辑（异常降级返回原值）。 */
    private String doMask(MaskType type, String value, String param) {
        if (value == null) {
            return null;
        }
        if (type == null || type == MaskType.NONE) {
            return value;
        }
        try {
            String masked = switch (type) {
                case MASK -> keepEnds(value, param);
                case REPLACE -> replace(value, param);
                case HASH -> hash(value, param);
                case GENERALIZE -> generalize(value, param);
                case PERTURB -> perturb(value);
                case ENCRYPT -> encrypt(value, param);
                case NONE -> value;
            };
            return masked == null ? value : masked;
        } catch (Exception e) {
            log.warn("脱敏执行失败，降级返回原值: type={}, err={}", type, e.getMessage());
            return value;
        }
    }

    // ===== MASK：保留前后各若干位 =====
    private String keepEnds(String value, String param) {
        int len = value.length();
        if (len == 0) {
            return value;
        }
        int front = 3;
        int back = 4;
        int[] nums = parseNumbers(param, 2);
        if (nums.length >= 1 && nums[0] >= 0) {
            front = nums[0];
        }
        if (nums.length >= 2 && nums[1] >= 0) {
            back = nums[1];
        }
        if (front + back >= len) {
            front = Math.min(front, len / 2);
            back = Math.min(back, Math.max(0, len - front - 1));
        }
        int maskLen = len - front - back;
        if (maskLen <= 0) {
            return value;
        }
        return value.substring(0, front) + "*".repeat(maskLen) + value.substring(len - back);
    }

    // ===== REPLACE：固定值替换 =====
    private String replace(String value, String param) {
        if (param == null || param.isBlank()) {
            return "***";
        }
        String p = param.trim();
        if (p.regionMatches(true, 0, "VALUE:", 0, 6)) {
            p = p.substring(6);
        }
        return p.isEmpty() ? "***" : p;
    }

    // ===== HASH：加盐 SHA-256 截断 =====
    private String hash(String value, String param) {
        String salt = cryptoKey;
        int length = 16;
        if (param != null && !param.isBlank()) {
            String p = param.trim();
            if (p.matches("\\d+")) {
                length = Math.min(Math.max(Integer.parseInt(p), 8), 64);
            } else {
                salt = p;
            }
        }
        String hex = CryptoUtil.sha256((salt + value).getBytes(StandardCharsets.UTF_8));
        return hex.substring(0, Math.min(length, hex.length()));
    }

    // ===== GENERALIZE：数值取整 / 日期截断 / 地址泛化 =====
    private String generalize(String value, String param) {
        String p = param == null ? "" : param.trim();
        String upper = p.toUpperCase(Locale.ROOT);
        if (upper.startsWith("NUM") || upper.startsWith("ROUND")) {
            return roundNumber(value, p, 100);
        }
        if (upper.startsWith("DATE") || upper.startsWith("TIME")) {
            return truncateDate(value, upper);
        }
        if (upper.startsWith("ADDR")) {
            return truncateAddress(value, p);
        }
        // 未显式指定类型时按值推断：数字→取整，日期→截月，其余→地址截区
        if (isNumber(value)) {
            return roundNumber(value, p, 100);
        }
        if (parseDate(value) != null) {
            return truncateDate(value, "MONTH");
        }
        return truncateAddress(value, p);
    }

    /** 数值按步长取整，如 NUM:100 → 12345 变成 12300。 */
    private String roundNumber(String value, String param, int defaultStep) {
        int step = defaultStep;
        int[] nums = parseNumbers(param, 1);
        if (nums.length >= 1 && nums[0] > 0) {
            step = nums[0];
        }
        BigDecimal number = new BigDecimal(value.trim());
        BigDecimal rounded = number.divide(BigDecimal.valueOf(step), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(step));
        return rounded.stripTrailingZeros().toPlainString();
    }

    /** 日期截断：YEAR→年，MONTH→月（默认），DAY→日。 */
    private String truncateDate(String value, String unit) {
        LocalDate date = parseDate(value);
        if (date == null) {
            return value;
        }
        String u = unit == null ? "" : unit.toUpperCase(Locale.ROOT);
        if (u.contains("YEAR")) {
            return date.getYear() + "-01-01";
        }
        if (u.contains("DAY")) {
            return date.toString();
        }
        return String.format("%d-%02d", date.getYear(), date.getMonthValue());
    }

    /** 地址泛化：默认截到区/县（含），无行政区划时保留前 6 个字符。 */
    private String truncateAddress(String value, String param) {
        int maxChars = 0;
        int[] nums = parseNumbers(param, 1);
        if (nums.length >= 1 && nums[0] > 0) {
            maxChars = nums[0];
        }
        if (maxChars > 0) {
            return value.length() <= maxChars ? value : value.substring(0, maxChars) + "***";
        }
        int districtIdx = -1;
        for (char mark : new char[]{'区', '县'}) {
            int idx = value.indexOf(mark);
            if (idx > districtIdx) {
                districtIdx = idx;
            }
        }
        if (districtIdx >= 0 && districtIdx < value.length() - 1) {
            return value.substring(0, districtIdx + 1) + "***";
        }
        if (value.length() > 6) {
            return value.substring(0, 6) + "***";
        }
        return value;
    }

    // ===== PERTURB：按密钥流逐位加扰（持密钥可按同一密钥流逆向还原） =====
    private String perturb(String value) {
        String keyStream = CryptoUtil.sha256((cryptoKey + ":perturb").getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int offset = Math.floorMod(keyStream.charAt(i % keyStream.length()), 9) + 1;
            if (c >= '0' && c <= '9') {
                sb.append((char) ('0' + (c - '0' + offset) % 10));
            } else if (c >= 'a' && c <= 'z') {
                sb.append((char) ('a' + (c - 'a' + offset) % 26));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('A' + (c - 'A' + offset) % 26));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ===== ENCRYPT：AES-256-GCM 可逆加密 =====
    private String encrypt(String value, String param) {
        String suffix = (param == null || param.isBlank()) ? "" : ":" + param.trim();
        SecretKeySpec key = CryptoUtil.deriveKey(cryptoKey + suffix);
        return CryptoUtil.encrypt(value, key);
    }

    // ===== 工具方法 =====
    /** 从参数串中提取前 n 个整数，解析失败返回空数组。 */
    private int[] parseNumbers(String param, int max) {
        if (param == null || param.isBlank()) {
            return new int[0];
        }
        Matcher matcher = DIGITS.matcher(param);
        int[] result = new int[max];
        int count = 0;
        while (matcher.find() && count < max) {
            try {
                result[count++] = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // 超长数字忽略
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return trimmed;
    }

    private boolean isNumber(String value) {
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, formatter);
            } catch (Exception ignored) {
                // 尝试下一个格式
            }
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(v, formatter).toLocalDate();
            } catch (Exception ignored) {
                // 尝试下一个格式
            }
        }
        if (v.length() >= 10) {
            try {
                return LocalDate.parse(v.substring(0, 10));
            } catch (Exception ignored) {
                // 非法日期
            }
        }
        return null;
    }

    /** 不可逆/双向敏感算法按 1/20 采样记录脱敏日志。 */
    private void recordSensitiveSample(MaskType type, String masked) {
        if (type != MaskType.HASH && type != MaskType.ENCRYPT && type != MaskType.PERTURB) {
            return;
        }
        if (sampleCounter.incrementAndGet() % 20 != 0) {
            return;
        }
        maskLogService.record(null, type, null, null, masked, "MASK_ENGINE", null, null, 0L);
    }
}
