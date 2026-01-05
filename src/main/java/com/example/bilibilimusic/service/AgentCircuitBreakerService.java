package com.example.bilibilimusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Agent 全局熔断器
 *
 * 基于 Redis 的简单熔断实现：
 * - 在滚动时间窗口内统计失败次数
 * - 超过阈值后进入 OPEN 状态，一段时间内拒绝新请求
 * - 冷却时间到期后自动恢复
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCircuitBreakerService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String FAILURE_KEY = "agent:circuit:failures";
    private static final String OPENED_AT_KEY = "agent:circuit:openedAt";

    // 失败统计窗口（秒）
    private static final long WINDOW_SECONDS = 300; // 5 分钟
    // 熔断触发阈值（窗口内失败次数）
    private static final long FAILURE_THRESHOLD = 10;
    // 熔断冷却时间（秒）
    private static final long COOLDOWN_SECONDS = 120; // 2 分钟

    /**
     * 是否允许执行（返回 false 表示熔断打开）
     */
    public boolean allowExecution() {
        boolean open = isOpen();
        if (open) {
            log.warn("[CircuitBreaker] 熔断已开启，拒绝新执行");
        }
        return !open;
    }

    /**
     * 记录一次成功执行
     */
    public void recordSuccess() {
        // 可以在这里按需降低失败计数，这里先保持简单实现，不做减计
    }

    /**
     * 记录一次失败执行
     */
    public void recordFailure(Throwable t) {
        try {
            Long failures = stringRedisTemplate.opsForValue().increment(FAILURE_KEY);
            stringRedisTemplate.expire(FAILURE_KEY, WINDOW_SECONDS, TimeUnit.SECONDS);
            long count = failures != null ? failures : 0L;
            log.warn("[CircuitBreaker] 记录失败: count={}", count, t);
            if (count >= FAILURE_THRESHOLD && !isOpen()) {
                // 进入 OPEN 状态
                long now = System.currentTimeMillis();
                stringRedisTemplate.opsForValue().set(OPENED_AT_KEY, String.valueOf(now), COOLDOWN_SECONDS, TimeUnit.SECONDS);
                log.error("[CircuitBreaker] 触发熔断: count={} (threshold={})", count, FAILURE_THRESHOLD);
            }
        } catch (Exception e) {
            log.error("[CircuitBreaker] 记录失败时出错", e);
        }
    }

    /**
     * 是否处于 OPEN 状态
     */
    public boolean isOpen() {
        try {
            String openedAtStr = stringRedisTemplate.opsForValue().get(OPENED_AT_KEY);
            if (openedAtStr == null) {
                return false;
            }
            long openedAt;
            try {
                openedAt = Long.parseLong(openedAtStr);
            } catch (NumberFormatException e) {
                // 异常数据，直接认为未熔断
                stringRedisTemplate.delete(OPENED_AT_KEY);
                return false;
            }
            long now = System.currentTimeMillis();
            if (now - openedAt < COOLDOWN_SECONDS * 1000L) {
                return true;
            }
            // 冷却时间已到，自动恢复
            stringRedisTemplate.delete(OPENED_AT_KEY);
            stringRedisTemplate.delete(FAILURE_KEY);
            log.info("[CircuitBreaker] 熔断冷却结束，自动恢复");
            return false;
        } catch (Exception e) {
            log.error("[CircuitBreaker] 判断熔断状态失败", e);
            return false;
        }
    }
}
