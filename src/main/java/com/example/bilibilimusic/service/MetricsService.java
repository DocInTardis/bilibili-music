package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.ExecutionMetrics;
import com.example.bilibilimusic.dto.ExecutionTrace;
import com.example.bilibilimusic.dto.NodeTrace;
import com.example.bilibilimusic.skill.CurationSkill;
import com.example.bilibilimusic.config.AgentExecutionConfig;
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
    
    private final CurationSkill curationSkill;
    private final AgentMetricsService agentMetricsService;
    private final AgentExecutionConfig agentExecutionConfig;
        
    // 关键路径性能告警阈值（毫秒）
    private static final long SLOW_NODE_WARN_THRESHOLD_MS = 2_000L;      // 单节点超过 2s
    private static final long TOTAL_EXEC_WARN_THRESHOLD_MS = 15_000L;    // 总执行超过 15s
    private static final long LLM_AVG_WARN_THRESHOLD_MS = 1_500L;        // LLM 平均响应超过 1.5s
            
    /**
     * 从执行追踪和上下文中计算指标
     */
    public ExecutionMetrics calculateMetrics(ExecutionTrace trace, PlaylistContext context, String strategy) {
        ExecutionMetrics metrics = ExecutionMetrics.builder()
            .executionId(trace.getExecutionId())
            .conversationId(trace.getConversationId())
            .playlistId(trace.getPlaylistId())
            .strategy(strategy)
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
                    
        // 基于评估指标自适应调整 Curation 阈值
        autoTuneCurationThresholds(metrics);
                    
        // 将本次执行指标汇总到按策略粒度的全局计数器，支持 A/B 分析
        agentMetricsService.recordStrategyExecutionMetrics(metrics);
                    
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
     * 基于执行指标自动调整 CurationSkill 的 LLM 阈值
     */
    private void autoTuneCurationThresholds(ExecutionMetrics metrics) {
        if (metrics == null) {
            return;
        }
        Integer totalEvaluated = metrics.getTotalEvaluated();
        Double hitRate = metrics.getHitRate();
        Double acceptanceRate = metrics.getAcceptanceRate();
            
        // 样本太少时不调参，避免抖动
        if (totalEvaluated == null || totalEvaluated < 5 || hitRate == null || acceptanceRate == null) {
            return;
        }
            
        int currentLow = curationSkill.getLlmThresholdLow();
        int currentHigh = curationSkill.getLlmThresholdHigh();
            
        // 命中率和接受率过低：认为筛选过于严格，稍微放宽阈值
        if (hitRate < 0.3 && acceptanceRate < 0.2) {
            int newLow = Math.max(0, currentLow - 1);
            int newHigh = Math.max(newLow + 1, currentHigh - 1);
            curationSkill.setLlmThresholds(newLow, newHigh);
            log.info("[AutoTune] 命中率/接受率偏低，放宽 LLM 阈值: low {} -> {}, high {} -> {} (hitRate={}, acceptanceRate={})",
                currentLow, newLow, currentHigh, newHigh, hitRate, acceptanceRate);
        }
            
        // 如果后续需要，也可以在命中率过高时收紧阈值，这里先保守只在效果差时调整
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
    
        // 关键路径性能告警（仅打印日志，后续可接入告警系统）
        long slowNodeThreshold = agentExecutionConfig.getSlowNodeWarnThresholdMs();
        long totalExecThreshold = agentExecutionConfig.getTotalExecWarnThresholdMs();
        long llmAvgThreshold = agentExecutionConfig.getLlmAvgWarnThresholdMs();
        if (metrics.getSlowestNodeDuration() != null
                && metrics.getSlowestNodeDuration() > slowNodeThreshold) {
            log.warn("[PerfWarn] 最慢节点耗时过长: node={} duration={}ms (threshold={}ms)",
                metrics.getSlowestNode(), metrics.getSlowestNodeDuration(), slowNodeThreshold);
        }
        if (metrics.getTotalExecutionTime() != null
                && metrics.getTotalExecutionTime() > totalExecThreshold) {
            log.warn("[PerfWarn] 总执行时间过长: total={}ms (threshold={}ms)",
                metrics.getTotalExecutionTime(), totalExecThreshold);
        }
        if (metrics.getLlmAvgResponseTime() != null
                && metrics.getLlmAvgResponseTime() > llmAvgThreshold) {
            log.warn("[PerfWarn] LLM 平均响应时间过长: avg={}ms (threshold={}ms)",
                metrics.getLlmAvgResponseTime(), llmAvgThreshold);
        }
        log.info("==================================");
    }
}
