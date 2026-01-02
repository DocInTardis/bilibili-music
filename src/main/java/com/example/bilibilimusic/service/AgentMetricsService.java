package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.AgentRuntimeMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Agent Metrics 服务
 * 
 * 监控指标：
 * 1. LLM 调用次数和平均响应时间
 * 2. 缓存命中率
 * 3. 各节点执行性能
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentMetricsService {
    
    private final StringRedisTemplate stringRedisTemplate;
    
    /**
     * 内存中的 Metrics 存储（按 playlistId）
     */
    private final ConcurrentHashMap<Long, AgentRuntimeMetrics> metricsStore = new ConcurrentHashMap<>();
    
    /**
     * 获取或创建 Metrics
     */
    public AgentRuntimeMetrics getOrCreateMetrics(Long playlistId, Long conversationId) {
        return metricsStore.computeIfAbsent(playlistId, id -> 
            AgentRuntimeMetrics.builder()
                .playlistId(playlistId)
                .conversationId(conversationId)
                .build()
        );
    }
    
    /**
     * 记录 LLM 调用
     */
    public void recordLLMCall(Long playlistId, long durationMs) {
        AgentRuntimeMetrics metrics = metricsStore.get(playlistId);
        if (metrics != null) {
            metrics.recordLLMCall(durationMs);
            log.debug("[Metrics] LLM 调用: playlistId={}, duration={}ms, total={}", 
                playlistId, durationMs, metrics.getLlmCallCount());
            
            // 更新 Redis 计数器
            incrementRedisCounter("agent:metrics:llm:calls");
            addToRedisTimer("agent:metrics:llm:duration", durationMs);
        }
    }
    
    /**
     * 记录缓存查询
     */
    public void recordCacheQuery(Long playlistId, boolean hit) {
        AgentRuntimeMetrics metrics = metricsStore.get(playlistId);
        if (metrics != null) {
            metrics.recordCacheQuery(hit);
            log.debug("[Metrics] 缓存查询: playlistId={}, hit={}, ratio={}%", 
                playlistId, hit, String.format("%.2f", metrics.getCacheHitRatio()));
            
            // 更新 Redis 计数器
            incrementRedisCounter("agent:metrics:cache:queries");
            if (hit) {
                incrementRedisCounter("agent:metrics:cache:hits");
            }
        }
    }
    
    /**
     * 记录节点执行
     */
    public void recordNodeExecution(Long playlistId, String nodeName, long durationMs) {
        AgentRuntimeMetrics metrics = metricsStore.get(playlistId);
        if (metrics != null) {
            metrics.recordNodeExecution(nodeName, durationMs);
            log.debug("[Metrics] 节点执行: playlistId={}, node={}, duration={}ms", 
                playlistId, nodeName, durationMs);
            
            // 更新 Redis 计数器
            incrementRedisCounter("agent:metrics:node:" + nodeName + ":executions");
            addToRedisTimer("agent:metrics:node:" + nodeName + ":duration", durationMs);
        }
    }
    
    /**
     * 完成 Metrics 记录
     */
    public AgentRuntimeMetrics finishMetrics(Long playlistId, long totalExecutionTimeMs, 
                                            boolean success, String errorMessage) {
        AgentRuntimeMetrics metrics = metricsStore.get(playlistId);
        if (metrics != null) {
            metrics.setTotalExecutionTimeMs(totalExecutionTimeMs);
            metrics.setSuccess(success);
            metrics.setErrorMessage(errorMessage);
            
            // 打印汇总
            logMetricsSummary(metrics);
            
            // 清理内存
            metricsStore.remove(playlistId);
        }
        return metrics;
    }
    
    /**
     * 打印 Metrics 汇总
     */
    private void logMetricsSummary(AgentRuntimeMetrics metrics) {
        log.info("=".repeat(60));
        log.info("[Metrics] 执行指标汇总 - playlistId={}", metrics.getPlaylistId());
        log.info("[Metrics] LLM 调用: {}次, 总耗时: {}ms, 平均: {}ms", 
            metrics.getLlmCallCount(), 
            metrics.getLlmTotalDurationMs(),
            String.format("%.2f", metrics.getLlmAvgResponseTimeMs()));
        log.info("[Metrics] 缓存命中率: {}% ({}/{})", 
            String.format("%.2f", metrics.getCacheHitRatio()),
            metrics.getCacheHitCount(),
            metrics.getCacheQueryCount());
        log.info("[Metrics] 节点执行次数: {}", metrics.getNodeExecutionCounts());
        log.info("[Metrics] 总执行时间: {}ms", metrics.getTotalExecutionTimeMs());
        log.info("[Metrics] 执行结果: {}", metrics.getSuccess() ? "成功" : "失败");
        log.info("=".repeat(60));
    }
    
    /**
     * 获取全局 Metrics
     */
    public GlobalMetrics getGlobalMetrics() {
        Long totalLLMCalls = getRedisCounter("agent:metrics:llm:calls");
        Long totalCacheQueries = getRedisCounter("agent:metrics:cache:queries");
        Long totalCacheHits = getRedisCounter("agent:metrics:cache:hits");
        
        double cacheHitRatio = totalCacheQueries > 0 
            ? (double) totalCacheHits / totalCacheQueries * 100 
            : 0.0;
        
        return new GlobalMetrics(totalLLMCalls, totalCacheQueries, totalCacheHits, cacheHitRatio);
    }
    
    /**
     * Redis 计数器增加
     */
    private void incrementRedisCounter(String key) {
        try {
            stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
        } catch (Exception e) {
            log.error("[Metrics] Redis 计数器增加失败: key={}", key, e);
        }
    }
    
    /**
     * Redis 计数器获取
     */
    private Long getRedisCounter(String key) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            log.error("[Metrics] Redis 计数器读取失败: key={}", key, e);
            return 0L;
        }
    }
    
    /**
     * Redis 计时器累加
     */
    private void addToRedisTimer(String key, long durationMs) {
        try {
            stringRedisTemplate.opsForValue().increment(key, durationMs);
            stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
        } catch (Exception e) {
            log.error("[Metrics] Redis 计时器累加失败: key={}", key, e);
        }
    }
    
    /**
     * 全局 Metrics 数据类
     */
    public static class GlobalMetrics {
        public final long totalLLMCalls;
        public final long totalCacheQueries;
        public final long totalCacheHits;
        public final double cacheHitRatio;
        
        public GlobalMetrics(long totalLLMCalls, long totalCacheQueries, 
                           long totalCacheHits, double cacheHitRatio) {
            this.totalLLMCalls = totalLLMCalls;
            this.totalCacheQueries = totalCacheQueries;
            this.totalCacheHits = totalCacheHits;
            this.cacheHitRatio = cacheHitRatio;
        }
    }
}
