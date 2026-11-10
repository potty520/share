package com.share.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图形验证码服务（FR-13 登录防刷）。
 * <p>生成 4 位字符 + 噪点/干扰线/倾斜的 PNG 图片，返回 data URI 供登录页直接展示；
 * 验证码默认 2 分钟有效、最多校验 5 次，校验成功后立即失效。
 * 单机部署走内存缓存，开启 Redis 后写入 Redis，保证多实例部署时跨节点校验通过。</p>
 */
@Slf4j
@Service
public class CaptchaService {

    /** 去掉易混淆字符（I/L/O/Z/0/1），人工识别更稳 */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXY23456789";
    private static final int WIDTH = 132;
    private static final int HEIGHT = 44;
    private static final String REDIS_PREFIX = "share:captcha:";
    private static final String REDIS_ATTEMPT_PREFIX = "share:captcha:try:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    @Value("${share.auth.captcha.length:4}")
    private int length = 4;

    @Value("${share.auth.captcha.ttl-seconds:120}")
    private int ttlSeconds = 120;

    @Value("${share.auth.captcha.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${share.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${share.auth.captcha.expose-code:false}")
    private boolean exposeCode;

    /** 是否关闭校验（自动化测试/联调可临时关闭）。 */
    @Value("${share.auth.captcha.enabled:true}")
    private boolean enabled = true;

    public CaptchaService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** 生成一张新的验证码。 */
    public CaptchaChallenge generate() {
        prune();
        int len = Math.min(Math.max(length, 3), 6);
        StringBuilder code = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        String text = code.toString();
        long expireAt = System.currentTimeMillis() + ttlMillis();
        store.put(captchaId, new Entry(text, expireAt, new AtomicInteger()));
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.opsForValue().set(REDIS_PREFIX + captchaId, text, Duration.ofMillis(ttlMillis()));
            } catch (Exception e) {
                log.warn("图形验证码写入 Redis 失败，回退内存：{}", e.getMessage());
            }
        }
        return new CaptchaChallenge(captchaId, render(text), exposeCode ? text : null,
                (int) (ttlMillis() / 1000));
    }

    /** 校验验证码；成功后立即失效，失败累计到上限后作废。 */
    public boolean verify(String captchaId, String input) {
        if (!enabled) {
            return true;
        }
        if (captchaId == null || captchaId.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String id = captchaId.trim();
        String expected = expectedOf(id);
        if (expected == null) {
            return false;
        }
        int attempts = consumeAttempt(id);
        if (attempts > Math.max(maxAttempts, 1)) {
            remove(id);
            return false;
        }
        if (!expected.equalsIgnoreCase(input.trim())) {
            return false;
        }
        remove(id);
        return true;
    }

    /** 校验开关（关闭时登录不再索要验证码，供脚本联调使用）。 */
    public boolean enabled() {
        return enabled;
    }

    private String expectedOf(String id) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(REDIS_PREFIX + id);
                if (value != null) {
                    return value;
                }
            } catch (Exception e) {
                log.warn("图形验证码读取 Redis 失败，回退内存：{}", e.getMessage());
            }
        }
        Entry entry = store.get(id);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt() < System.currentTimeMillis()) {
            store.remove(id);
            return null;
        }
        return entry.code();
    }

    private int consumeAttempt(String id) {
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                Long count = redis.opsForValue().increment(REDIS_ATTEMPT_PREFIX + id);
                if (count != null && count == 1L) {
                    redis.expire(REDIS_ATTEMPT_PREFIX + id, Duration.ofMillis(ttlMillis()));
                }
                return count == null ? 0 : count.intValue();
            } catch (Exception e) {
                log.warn("图形验证码计数失败，回退内存：{}", e.getMessage());
            }
        }
        Entry entry = store.get(id);
        if (entry == null) {
            return Integer.MAX_VALUE;
        }
        return entry.attempts().incrementAndGet();
    }

    private void remove(String id) {
        store.remove(id);
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.delete(REDIS_PREFIX + id);
                redis.delete(REDIS_ATTEMPT_PREFIX + id);
            } catch (Exception e) {
                log.debug("图形验证码清理失败：{}", e.getMessage());
            }
        }
    }

    /** 清理过期条目，避免内存缓存长期增长（每次生成前顺带执行）。 */
    private void prune() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }

    private long ttlMillis() {
        return Math.max(30, ttlSeconds) * 1000L;
    }

    private StringRedisTemplate redis() {
        if (!redisEnabled) {
            return null;
        }
        try {
            return redisProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    /** 渲染验证码图片：浅色渐变底 + 干扰线/噪点 + 逐字符旋转倾斜。 */
    private String render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(238, 242, 255), WIDTH, HEIGHT, new Color(219, 229, 255)));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            for (int i = 0; i < 8; i++) {
                g.setColor(randomColor(150, 205));
                g.setStroke(new BasicStroke(1f + random.nextFloat()));
                g.drawLine(random.nextInt(WIDTH / 4), random.nextInt(HEIGHT),
                        WIDTH - random.nextInt(WIDTH / 4), random.nextInt(HEIGHT));
            }
            for (int i = 0; i < 90; i++) {
                g.setColor(randomColor(170, 225));
                g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }

            int fontSize = 28 + random.nextInt(3);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
            int slot = (WIDTH - 26) / Math.max(1, code.length());
            for (int i = 0; i < code.length(); i++) {
                AffineTransform old = g.getTransform();
                double angle = (random.nextDouble() - 0.5) * 0.5;
                int x = 13 + i * slot + random.nextInt(4);
                int y = (HEIGHT + fontSize) / 2 - 5 + random.nextInt(6);
                g.rotate(angle, x + slot / 2.0, HEIGHT / 2.0);
                g.setFont(font);
                g.setColor(new Color(28 + random.nextInt(40), 40 + random.nextInt(45), 120 + random.nextInt(80)));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(old);
            }

            g.setColor(new Color(199, 210, 254));
            g.drawRect(0, 0, WIDTH - 1, HEIGHT - 1);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("图形验证码生成失败", e);
        }
    }

    private Color randomColor(int min, int max) {
        int span = Math.max(1, max - min);
        return new Color(min + random.nextInt(span), min + random.nextInt(span), min + random.nextInt(span));
    }

    /** 验证码缓存项。 */
    private record Entry(String code, long expireAt, AtomicInteger attempts) {
    }

    /** 验证码响应：captchaId + 图片 data URI；exposeCode 开启时附带明文便于联调。 */
    public record CaptchaChallenge(String captchaId, String image, String code, int expiresIn) {
    }
}
