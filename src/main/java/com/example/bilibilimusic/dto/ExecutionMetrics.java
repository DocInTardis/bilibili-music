package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行评估指标
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionMetrics {
    
    /**
     * 执行ID
     */
    private String executionId;
    
    /**
     * 会话ID
     */
    private Long conversationId;
    
    /**
     * 播放列表ID
     */
    private Long playlistId;
        
    /**
     * 使用的策略名/Policy（用于 A/B 对比）
     */
    private String strategy;
        
    /**
     * ========== 搜索与推荐指标 ==========
     */
    
    /**
     * 搜索到的视频总数
     */
    private Integer totalSearched;
    
    /**
     * 实际判断的视频数（可能因达标提前结束）
     */
    private Integer totalEvaluated;
    
    /**
     * 接受的视频数
     */
    private Integer totalAccepted;
    
    /**
     * 拒绝的视频数
     */
    private Integer totalRejected;
    
    /**
     * 目标数量
     */
    private Integer targetCount;
    
    /**
     * 是否达标
     */
    private Boolean targetReached;
    
    /**
     * ========== 命中率与接受率 ==========
     */
    
    /**
     * 命中率 = 接受数 / 判断数
     */
    private Double hitRate;
    
    /**
     * 用户接受率 = 接受数 / 搜索数（整体质量）
     */
    private Double acceptanceRate;
    
    /**
     * 目标完成率 = 接受数 / 目标数
     */
    private Double targetCompletionRate;
    
    /**
     * ========== LLM 调用统计 ==========
     */
    
    /**
     * LLM 总调用次数
     */
    private Integer llmCallCount;
    
    /**
     * LLM 平均响应时间（毫秒）
     */
    private Long llmAvgResponseTime;
    
    /**
     * LLM 总耗时（毫秒）
     */
    private Long llmTotalTime;
    
    /**
     * ========== 性能指标 ==========
     */
    
    /**
     * 总执行时间（毫秒）
     */
    private Long totalExecutionTime;
    
    /**
     * 平均每个视频处理时间（毫秒）
     */
    private Long avgVideoProcessTime;
    
    /**
     * 最慢的节点
     */
    private String slowestNode;
    
    /**
     * 最慢节点耗时（毫秒）
     */
    private Long slowestNodeDuration;
    
    /**
     * 计算衍生指标
     */
    public void calculateDerivedMetrics() {
        // 命中率
        if (totalEvaluated != null && totalEvaluated > 0) {
            hitRate = (double) totalAccepted / totalEvaluated;
        } else {
            hitRate = 0.0;
        }
        
        // 接受率
        if (totalSearched != null && totalSearched > 0) {
            acceptanceRate = (double) totalAccepted / totalSearched;
        } else {
            acceptanceRate = 0.0;
        }
        
        // 目标完成率
        if (targetCount != null && targetCount > 0) {
            targetCompletionRate = (double) totalAccepted / targetCount;
        } else {
            targetCompletionRate = 1.0; // 无目标时视为100%
        }
        
        // 平均视频处理时间
        if (totalEvaluated != null && totalEvaluated > 0 && totalExecutionTime != null) {
            avgVideoProcessTime = totalExecutionTime / totalEvaluated;
        } else {
            avgVideoProcessTime = 0L;
        }
        
        // LLM 平均响应时间
        if (llmCallCount != null && llmCallCount > 0 && llmTotalTime != null) {
            llmAvgResponseTime = llmTotalTime / llmCallCount;
        } else {
            llmAvgResponseTime = 0L;
        }
    }
    
    /**
     * 获取指标摘要
     */
    public String getSummary() {
        return String.format(
            "指标摘要: 命中率%.1f%%, 接受率%.1f%%, LLM调用%d次, 总耗时%dms",
            hitRate * 100, acceptanceRate * 100, llmCallCount, totalExecutionTime
        );
    }
}
