package com.share.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌吊销黑名单（Redis 缺失时的内存实现）：登出后 access/refresh 令牌立即失效，
 * 由 {@link com.share.auth.filter.TokenRevocationFilter} 在请求链路中校验。
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "share:auth:revoked:";

    /** token → 过期时间戳（毫秒），过期后自动清理 */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Value("${share.redis.enabled:false}")
    private boolean redisEnabled;

    public TokenBlacklistService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** 吊销令牌，ttlMillis 建议取令牌剩余有效期。 */
    public void revoke(String token, long ttlMillis) {
        if (token == null || token.isBlank()) {
            return;
        }
        long expireAt = System.currentTimeMillis() + Math.max(ttlMillis, 60_000L);
        revoked.put(token.trim(), expireAt);
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                redis.opsForValue().set(KEY_PREFIX + token.trim(), "1", Duration.ofMillis(Math.max(ttlMillis, 60_000L)));
            } catch (Exception e) {
                log.warn("Redis 令牌黑名单写入失败，保留内存兜底：{}", e.getMessage());
            }
        }
    }

    /** 判断令牌是否已吊销。 */
    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                if (Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + token.trim()))) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("Redis 令牌黑名单读取失败，回退内存：{}", e.getMessage());
            }
        }
        Long expireAt = revoked.get(token.trim());
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            revoked.remove(token.trim());
            return false;
        }
        return true;
    }

    /** 每分钟清理已过期条目，防止内存膨胀。 */
    @Scheduled(fixedDelay = 60_000L)
    public void cleanup() {
        long now = System.currentTimeMillis();
        int before = revoked.size();
        revoked.entrySet().removeIf(e -> e.getValue() < now);
        if (before != revoked.size()) {
            log.debug("令牌黑名单清理: {} -> {}", before, revoked.size());
        }
    }

    public int size() {
        return revoked.size();
    }

    /** 当前后端：REDIS / MEMORY。 */
    public String backend() {
        return redis() == null ? "MEMORY" : "REDIS";
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
}
