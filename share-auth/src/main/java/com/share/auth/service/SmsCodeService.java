package com.share.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 短信验证码服务（FR-13 双因素）：6 位数字、内存缓存 5 分钟、最多校验 5 次。
 * <p>演示环境不接入真实短信通道，验证码随接口返回；生产应接入运营商网关并做发送频控。</p>
 */
@Slf4j
@Service
public class SmsCodeService {

    /** 有效期 5 分钟 */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    /** 单个验证码最大校验次数 */
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /** 验证码缓存项。 */
    private record Entry(String code, long expireAt, AtomicInteger attempts) {
    }

    /** 生成并缓存验证码（同一账号重新获取会覆盖旧码）。 */
    public String generate(String username) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        store.put(username, new Entry(code, System.currentTimeMillis() + TTL_MILLIS, new AtomicInteger(0)));
        log.debug("生成短信验证码: user={}", username);
        return code;
    }

    /** 校验验证码，成功即失效。 */
    public boolean verify(String username, String code) {
        if (username == null || code == null || code.isBlank()) {
            return false;
        }
        Entry entry = store.get(username);
        if (entry == null) {
            return false;
        }
        if (entry.expireAt() < System.currentTimeMillis()) {
            store.remove(username);
            return false;
        }
        if (entry.attempts().incrementAndGet() > MAX_ATTEMPTS) {
            store.remove(username);
            return false;
        }
        if (!entry.code().equals(code.trim())) {
            return false;
        }
        store.remove(username);
        return true;
    }

    /** 验证码有效期（秒）。 */
    public int expiresInSeconds() {
        return (int) (TTL_MILLIS / 1000);
    }

    /** 清空全部验证码（测试/登出场景）。 */
    public void clear() {
        store.clear();
    }
}
