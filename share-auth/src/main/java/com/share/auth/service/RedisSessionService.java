package com.share.auth.service;

import com.share.common.security.LoginUser;
import com.share.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Redis 登录会话登记：记录 access/refresh 令牌摘要、用户与有效期，支撑集群登录态与登出清理。 */
@Slf4j
@Service
public class RedisSessionService {

    private static final String SESSION_PREFIX = "share:session:";
    private static final String USER_PREFIX = "share:user-sessions:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Value("${share.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${share.redis.session-enabled:true}")
    private boolean sessionEnabled;

    @Value("${share.jwt.refresh-days:7}")
    private long refreshDays;

    public RedisSessionService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    public void register(LoginUser user, String accessToken, String refreshToken, String clientIp) {
        StringRedisTemplate redis = redis();
        if (redis == null || user == null) {
            return;
        }
        try {
            Duration ttl = Duration.ofDays(Math.max(1, refreshDays));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", user.getUserId());
            data.put("username", user.getUsername());
            data.put("realName", user.getRealName());
            data.put("roleCode", user.getRoleCode());
            data.put("clientIp", clientIp);
            data.put("loginAt", Instant.now().toString());
            String sessionId = digest(accessToken);
            redis.opsForValue().set(SESSION_PREFIX + sessionId, JsonUtil.toJson(data), ttl);
            redis.opsForSet().add(USER_PREFIX + user.getUserId(), sessionId);
            redis.expire(USER_PREFIX + user.getUserId(), ttl);
            if (refreshToken != null && !refreshToken.isBlank()) {
                redis.opsForValue().set(SESSION_PREFIX + digest(refreshToken), JsonUtil.toJson(data), ttl);
            }
        } catch (Exception e) {
            log.warn("Redis 会话登记失败：{}", e.getMessage());
        }
    }

    public void remove(String token) {
        StringRedisTemplate redis = redis();
        if (redis == null || token == null || token.isBlank()) {
            return;
        }
        try {
            redis.delete(SESSION_PREFIX + digest(token));
        } catch (Exception e) {
            log.warn("Redis 会话清理失败：{}", e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", redisEnabled && sessionEnabled);
        result.put("backend", redis() == null ? "MEMORY" : "REDIS");
        result.put("refreshTtlDays", refreshDays);
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                result.put("sessionKeys", redis.keys(SESSION_PREFIX + "*").size());
            } catch (Exception e) {
                result.put("error", e.getMessage());
            }
        }
        return result;
    }

    private StringRedisTemplate redis() {
        if (!redisEnabled || !sessionEnabled) {
            return null;
        }
        try {
            return redisProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    private String digest(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(token.hashCode());
        }
    }
}
