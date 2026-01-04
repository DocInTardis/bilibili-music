package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 运行时指标
 * 
 * 用于监控和优化：
 * 1. LLM 调用次数和耗时
 * 2. 缓存命中率
 * 3. 各节点性能
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeMetrics {
    
    /**
     * 播放列表ID
     */
    private Long playlistId;
    
    /**
     * 会话ID
     */
    private Long conversationId;
        
    /**
     * 使用的策略名/Policy（用于 A/B 分析与对比）
     */
    private String strategy;
        
    /**
     * LLM 调用次数
     */
    @Builder.Default
    private int llmCallCount = 0;
    
    /**
     * LLM 总耗时（毫秒）
     */
    @Builder.Default
    private long llmTotalDurationMs = 0;
    
    /**
     * LLM 平均响应时间（毫秒）
     */
    private Double llmAvgResponseTimeMs;
    
    /**
     * 缓存查询次数
     */
    @Builder.Default
    private int cacheQueryCount = 0;
    
    /**
     * 缓存命中次数
     */
    @Builder.Default
    private int cacheHitCount = 0;
    
    /**
     * 缓存命中率（百分比）
     */
    private Double cacheHitRatio;
    
    /**
     * 各节点执行次数
     * Key: nodeName
     * Value: 执行次数
     */
    @Builder.Default
    private Map<String, Integer> nodeExecutionCounts = new HashMap<>();
    
    /**
     * 各节点总耗时（毫秒）
     * Key: nodeName
     * Value: 总耗时
     */
    @Builder.Default
    private Map<String, Long> nodeTotalDurations = new HashMap<>();
    
    /**
     * 总执行时间（毫秒）
     */
    private Long totalExecutionTimeMs;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 增加 LLM 调用记录
     */
    public void recordLLMCall(long durationMs) {
        this.llmCallCount++;
        this.llmTotalDurationMs += durationMs;
        this.llmAvgResponseTimeMs = (double) this.llmTotalDurationMs / this.llmCallCount;
    }
    
    /**
     * 记录缓存查询
     */
    public void recordCacheQuery(boolean hit) {
        this.cacheQueryCount++;
        if (hit) {
            this.cacheHitCount++;
        }
        this.cacheHitRatio = this.cacheQueryCount > 0 
            ? (double) this.cacheHitCount / this.cacheQueryCount * 100 
            : 0.0;
    }
    
    /**
     * 记录节点执行
     */
    public void recordNodeExecution(String nodeName, long durationMs) {
        nodeExecutionCounts.merge(nodeName, 1, Integer::sum);
        nodeTotalDurations.merge(nodeName, durationMs, Long::sum);
    }
}
