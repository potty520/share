package com.share.share.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存令牌桶限流（FR-07 网关流量控制）。
 * <p>按凭据维度（AppKey）独立限流，速率取自共享 API 的 {@code qpsLimit}；
 * 单实例内存实现，集群部署时应替换为 Redis 令牌桶（架构文档 APISIX limit-req 插件等价能力）。</p>
 */
@Slf4j
@Service
public class RateLimitService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试获取一次调用许可。
     *
     * @param key 限流键（AppKey）
     * @param qps QPS 限额，小于 1 时按 1 处理
     * @return true 表示放行
     */
    public boolean tryAcquire(String key, Integer qps) {
        int q = qps == null || qps <= 0 ? 1 : qps;
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(q));
        if (bucket.capacity != q) {
            // 限额调整后重建桶，避免历史令牌造成误放行
            bucket = new Bucket(q);
            buckets.put(key, bucket);
        }
        return bucket.tryAcquire(q);
    }

    /** 当前限流键数量（运维观测用）。 */
    public int bucketCount() {
        return buckets.size();
    }

    /** 令牌桶：容量 = qps，按 qps 个/秒匀速补充。 */
    private static final class Bucket {
        private final int capacity;
        private double tokens;
        private long lastRefillNanos;

        private Bucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized boolean tryAcquire(int qps) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * qps);
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
