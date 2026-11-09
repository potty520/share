package com.share.share.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享 API 熔断器（FR-07）。
 * <p>按 API 编码统计窗口内失败次数，达到阈值后打开熔断，窗口期内直接快速失败；
 * 冷却结束后进入半开态，只放行一个探测请求，成功则闭合、失败则继续打开。
 * 单实例内存实现；集群部署时可替换为 Redis 共享状态。</p>
 */
@Slf4j
@Service
public class CircuitBreakerService {

    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Value("${share.gateway.circuit.failure-threshold:5}")
    private int failureThreshold = 5;

    @Value("${share.gateway.circuit.window-seconds:60}")
    private int windowSeconds = 60;

    @Value("${share.gateway.circuit.open-seconds:30}")
    private int openSeconds = 30;

    /** 是否放行本次请求（熔断打开时返回 false）。 */
    public boolean allow(String key) {
        if (key == null) {
            return true;
        }
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.openUntil > now) {
                return false;
            }
            if (state.openUntil > 0) {
                // 冷却结束：进入半开态，只允许一个探测请求
                if (state.probing) {
                    return false;
                }
                state.probing = true;
                state.openUntil = 0;
                log.info("熔断器半开探测 key={}", key);
            }
            return true;
        }
    }

    /** 调用成功：闭合熔断并清空失败窗口。 */
    public void recordSuccess(String key) {
        if (key == null) {
            return;
        }
        State state = states.get(key);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.probing = false;
            state.openUntil = 0;
            state.failures.clear();
        }
    }

    /** 调用失败：累计失败次数，达到阈值后打开熔断。 */
    public void recordFailure(String key) {
        if (key == null) {
            return;
        }
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.probing) {
                // 半开探测失败：立即重新打开
                state.probing = false;
                open(state, now, key, "半开探测失败");
                return;
            }
            state.failures.addLast(now);
            long windowStart = now - windowSeconds * 1000L;
            while (!state.failures.isEmpty() && state.failures.peekFirst() < windowStart) {
                state.failures.removeFirst();
            }
            if (state.failures.size() >= failureThreshold) {
                open(state, now, key, "窗口内失败 " + state.failures.size() + " 次");
            }
        }
    }

    private void open(State state, long now, String key, String reason) {
        state.openUntil = now + openSeconds * 1000L;
        state.failures.clear();
        log.warn("熔断器打开 key={} {}，冷却 {} 秒", key, reason, openSeconds);
    }

    /** 运维观测：当前熔断状态快照。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        states.forEach((key, state) -> {
            synchronized (state) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("open", state.openUntil > now);
                item.put("probing", state.probing);
                item.put("retryAfterMs", Math.max(0, state.openUntil - now));
                item.put("windowFailures", state.failures.size());
                result.put(key, item);
            }
        });
        return result;
    }

    /** 手工复位（运维应急）。 */
    public void reset(String key) {
        if (key == null) {
            states.clear();
            return;
        }
        State state = states.get(key);
        if (state != null) {
            synchronized (state) {
                state.openUntil = 0;
                state.probing = false;
                state.failures.clear();
            }
        }
    }

    private static final class State {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long openUntil;
        private boolean probing;
    }
}
