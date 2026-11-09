package com.share.share.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 集群级限流（FR-07）。
 * <p>按「限流键 + 秒级窗口」在数据库内原子累加计数，多实例部署时共享同一额度；
 * 相比单机内存令牌桶，可避免集群规模放大实际放行量。数据库不可用时由调用方回退内存桶。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterRateLimitService {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Value("${share.gateway.cluster-rate-limit.enabled:true}")
    private boolean enabled = true;

    @Value("${share.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${share.redis.rate-limit-enabled:true}")
    private boolean redisRateLimitEnabled;

    /** 尝试获取一次调用许可；异常时回退为放行（由调用方内存桶兜底）。 */
    public boolean tryAcquire(String key, int qps) {
        if (!enabled || key == null) {
            return true;
        }
        int limit = qps <= 0 ? 1 : qps;
        long window = System.currentTimeMillis() / 1000;
        String rateKey = key.length() > 160 ? key.substring(0, 160) : key;
        StringRedisTemplate redis = redis();
        if (redis != null) {
            try {
                String redisKey = "share:cluster-rate:" + rateKey + ":" + window;
                Long counter = redis.opsForValue().increment(redisKey);
                if (counter != null && counter == 1L) {
                    redis.expire(redisKey, Duration.ofSeconds(3));
                }
                return counter == null || counter <= limit;
            } catch (Exception e) {
                log.warn("Redis 集群限流失败，回退数据库计数：{}", e.getMessage());
            }
        }
        try {
            int updated = jdbcTemplate.update(
                    "update shr_gateway_rate set counter_value = case when window_start = ? then counter_value + 1 else 1 end, "
                            + "window_start = ? where rate_key = ?", window, window, rateKey);
            if (updated == 0) {
                try {
                    jdbcTemplate.update("insert into shr_gateway_rate(rate_key, window_start, counter_value) "
                            + "values(?, ?, ?)", rateKey, window, 1);
                } catch (DataAccessException race) {
                    // 并发插入冲突后重试累加
                    jdbcTemplate.update(
                            "update shr_gateway_rate set counter_value = case when window_start = ? then counter_value + 1 else 1 end, "
                                    + "window_start = ? where rate_key = ?", window, window, rateKey);
                }
            }
            Integer counter = jdbcTemplate.queryForObject(
                    "select counter_value from shr_gateway_rate where rate_key = ?", Integer.class, rateKey);
            return counter == null || counter <= limit;
        } catch (DataAccessException e) {
            log.warn("集群限流计数失败，回退内存限流：{}", e.getMessage());
            return true;
        }
    }

    /** 清理 3 分钟前的窗口计数，避免表无限增长。 */
    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpired() {
        if (!enabled) {
            return;
        }
        try {
            long cutoff = System.currentTimeMillis() / 1000 - 180;
            int deleted = jdbcTemplate.update("delete from shr_gateway_rate where window_start < ?", cutoff);
            if (deleted > 0) {
                log.debug("清理限流窗口计数 {} 条", deleted);
            }
        } catch (DataAccessException e) {
            log.debug("清理限流窗口计数失败：{}", e.getMessage());
        }
    }

    public String backend() {
        return redis() == null ? "DATABASE" : "REDIS";
    }

    private StringRedisTemplate redis() {
        if (!redisEnabled || !redisRateLimitEnabled) {
            return null;
        }
        try {
            return redisProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }
}
