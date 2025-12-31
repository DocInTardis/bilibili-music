package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.ExecutionMetrics;
import com.example.bilibilimusic.dto.ExecutionTrace;
import com.example.bilibilimusic.dto.NodeTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 指标服务 - 计算和记录执行评估指标
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetricsService {
    
    /**
     * 从执行追踪和上下文中计算指标
     */
    public ExecutionMetrics calculateMetrics(ExecutionTrace trace, PlaylistContext context) {
        ExecutionMetrics metrics = ExecutionMetrics.builder()
            .executionId(trace.getExecutionId())
            .conversationId(trace.getConversationId())
            .playlistId(trace.getPlaylistId())
            .build();
        
        // 搜索与推荐指标
        metrics.setTotalSearched(context.getSearchResults() != null ? context.getSearchResults().size() : 0);
        metrics.setTotalAccepted(context.getSelectedVideos() != null ? context.getSelectedVideos().size() : 0);
        metrics.setTotalRejected(context.getTrashVideos() != null ? context.getTrashVideos().size() : 0);
        metrics.setTotalEvaluated(context.getCurrentVideoIndex() + 1); // 实际判断的视频数
        metrics.setTargetCount(context.getIntent() != null ? context.getIntent().getTargetCount() : 0);
        metrics.setTargetReached(context.isTargetReached());
        
        // 性能指标
        metrics.setTotalExecutionTime(trace.getTotalDurationMs());
        
        // 分析节点追踪
        analyzeNodeTraces(trace, metrics);
        
        // 计算衍生指标
        metrics.calculateDerivedMetrics();
        
        log.info("[Metrics] {}", metrics.getSummary());
        
        return metrics;
    }
    
    /**
     * 分析节点追踪数据
     */
    private void analyzeNodeTraces(ExecutionTrace trace, ExecutionMetrics metrics) {
        List<NodeTrace> nodeTraces = trace.getNodeTraces();
        if (nodeTraces == null || nodeTraces.isEmpty()) {
            return;
        }
        
        // 统计 LLM 调用（假设关键词提取和总结节点会调用 LLM）
        int llmCalls = 0;
        long llmTotalTime = 0L;
        
        // 找出最慢的节点
        NodeTrace slowestNode = nodeTraces.stream()
            .max(Comparator.comparing(NodeTrace::getDurationMs))
            .orElse(null);
        
        if (slowestNode != null) {
            metrics.setSlowestNode(slowestNode.getNodeName());
            metrics.setSlowestNodeDuration(slowestNode.getDurationMs());
        }
        
        // 统计 LLM 相关节点
        for (NodeTrace nodeTrace : nodeTraces) {
            String nodeName = nodeTrace.getNodeName();
            if (nodeName.contains("keyword") || nodeName.contains("summary") || nodeName.contains("intent")) {
                llmCalls++;
                llmTotalTime += nodeTrace.getDurationMs();
            }
        }
        
        metrics.setLlmCallCount(llmCalls);
        metrics.setLlmTotalTime(llmTotalTime);
    }
    
    /**
     * 记录指标到日志（后续可扩展到数据库）
     */
    public void recordMetrics(ExecutionMetrics metrics) {
        log.info("========== 执行指标汇总 ==========");
        log.info("执行ID: {}", metrics.getExecutionId());
        log.info("会话ID: {}, 播放列表ID: {}", metrics.getConversationId(), metrics.getPlaylistId());
        log.info("搜索: {} 个, 判断: {} 个, 接受: {} 个, 拒绝: {} 个", 
            metrics.getTotalSearched(), metrics.getTotalEvaluated(), 
            metrics.getTotalAccepted(), metrics.getTotalRejected());
        log.info("命中率: {:.1f}%, 接受率: {:.1f}%, 完成率: {:.1f}%",
            metrics.getHitRate() * 100, metrics.getAcceptanceRate() * 100, 
            metrics.getTargetCompletionRate() * 100);
        log.info("LLM 调用: {} 次, 平均耗时: {}ms, 总耗时: {}ms",
            metrics.getLlmCallCount(), metrics.getLlmAvgResponseTime(), metrics.getLlmTotalTime());
        log.info("总执行时间: {}ms, 平均每视频: {}ms",
            metrics.getTotalExecutionTime(), metrics.getAvgVideoProcessTime());
        log.info("最慢节点: {} ({}ms)", metrics.getSlowestNode(), metrics.getSlowestNodeDuration());
        log.info("==================================");
    }
}
