package com.example.bilibilimusic.agent;

import com.example.bilibilimusic.agent.graph.PlaylistAgentGraph;
import com.example.bilibilimusic.agent.graph.PlaylistAgentGraphBuilder;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.ExecutionMetrics;
import com.example.bilibilimusic.dto.ExecutionTrace;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.entity.Conversation;
import com.example.bilibilimusic.entity.Playlist;
import com.example.bilibilimusic.service.ContextPersistenceService;
import com.example.bilibilimusic.service.DatabaseService;
import com.example.bilibilimusic.service.ExecutionLockService;
import com.example.bilibilimusic.service.MetricsService;
import com.example.bilibilimusic.service.AgentMetricsService;
import com.example.bilibilimusic.service.RecommendationExplanationService;
import com.example.bilibilimusic.service.AgentCircuitBreakerService;
import com.example.bilibilimusic.dto.AgentErrorCode;
import com.example.bilibilimusic.service.telemetry.AgentTraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * 歌单 Agent - 基于状态机的流程编排
 * 
 * ⚠️ 已重构为状态驱动的 LangGraph Agent：
 * - State = PlaylistContext（Agent 在任意时刻知道的一切）
 * - 将原来的 if/for/break 映射为条件边与循环边
 * - 思考路径显式可视化、可中断、可演进
 * 
 * 执行流程：
 * Intent Understanding → Keyword Extraction → Video Retrieval → 
 * [有结果?] ─No→ END
 *     ↓ Yes
 * Judge Video ──[继续?]─Yes→ Judge Video（循环）
 *     ↓ No
 * Target Evaluation → Generate Summary → END
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaylistAgent {
    
    private final PlaylistAgentGraphBuilder graphBuilder;
    private final SimpMessagingTemplate messagingTemplate;
    private final DatabaseService databaseService;
    private final MetricsService metricsService;
    private final ContextPersistenceService contextPersistenceService;
    private final ExecutionLockService executionLockService;
    private final AgentMetricsService agentMetricsService;
    private final RecommendationExplanationService explanationService;
    private final AgentCircuitBreakerService circuitBreakerService;
    
    /**
     * 执行歌单生成任务（使用状态机 + 持久化 + 锁）
     * @param request 用户请求
     * @param statusCallback 状态回调（用于 WebSocket 推送）
     * @return 歌单响应
     */
    public PlaylistResponse execute(PlaylistRequest request, Consumer<String> statusCallback) {
        log.info("=".repeat(60));
        log.info("[PlaylistAgent] 开始执行任务（状态机模式）");
        log.info("[PlaylistAgent] 用户输入：{}", request.getQuery());
        log.info("=".repeat(60));
                    
        // -1. 全局熔断判断
        if (!circuitBreakerService.allowExecution()) {
            log.warn("[PlaylistAgent] 全局熔断已开启，拒绝执行新任务");
            statusCallback.accept("⚠️ 系统当前较忙，已暂时暂停新的推荐任务，请稍后再试");
            return PlaylistResponse.builder()
                .videos(Collections.emptyList())
                .summary("系统当前较忙，已暂时暂停新的推荐任务，请稍后再试")
                .trashVideos(Collections.emptyList())
                .mp3Files(Collections.emptyList())
                .errorCode(AgentErrorCode.CIRCUIT_OPEN.name())
                .build();
        }
                    
        // 0. 创建或获取当前活跃会话，并创建播放列表
        Conversation conversation = databaseService.getOrCreateActiveConversation();
        Long conversationId = conversation.getId();
        Long userId = conversation.getUserId();
                    
        int targetCount = request.getLimit();
        Playlist playlist = databaseService.createPlaylist(
            conversationId, 
            request.getQuery(), 
            targetCount
        );
        Long playlistId = playlist.getId();
            
        log.info("[Database] 会话ID: {}, 播放列表ID: {}", conversationId, playlistId);
        
        // 1. 获取执行锁（防止并发执行同一 playlist）
        if (!executionLockService.tryLock(playlistId)) {
            log.warn("[PlaylistAgent] 播放列表正在执行中: playlistId={}", playlistId);
            statusCallback.accept("⚠️ 该播放列表正在生成中，请稍候...");
            return PlaylistResponse.builder()
                .videos(Collections.emptyList())
                .summary("该播放列表正在生成中")
                .trashVideos(Collections.emptyList())
                .mp3Files(Collections.emptyList())
                .errorCode(AgentErrorCode.PLAYLIST_LOCKED.name())
                .build();
        }
        
        try {
            // 2. 初始化或恢复 Context
            PlaylistContext context = initOrRestoreContext(request, playlistId, conversationId, userId);
                        
            // 3. 构建状态图（基于请求选择策略）
            PlaylistAgentGraph graph = graphBuilder.build(request);
            String strategy = graph.getPolicyName();
                        
            // 2.5 初始化 Runtime Metrics（附带策略信息，便于 A/B 分析）
            agentMetricsService.getOrCreateMetrics(playlistId, conversationId, strategy);
            long startTime = System.currentTimeMillis();

            // 将初始策略写入执行控制，便于后续热切换与调试
            if (context.getControl() != null) {
                context.getControl().setStrategyName(strategy);
            }
                        
            // 4. 执行图（定期保存上下文）
            statusCallback.accept("🎯 开始执行状态机...");
            executeWithPersistence(graph, context);
            
            // 5. 计算并记录指标
            ExecutionTrace trace = graph.getExecutionTrace();
            ExecutionMetrics metrics = metricsService.calculateMetrics(trace, context, strategy);
            metricsService.recordMetrics(metrics);
            
            // 基于执行结果推导错误码（如有）
            AgentErrorCode errorCode = null;
            if (trace != null && "TIMEOUT".equalsIgnoreCase(trace.getStatus())) {
                errorCode = AgentErrorCode.EXECUTION_TIMEOUT;
            } else if (metrics != null && metrics.getTotalSearched() != null
                    && metrics.getTotalSearched() == 0) {
                errorCode = AgentErrorCode.SEARCH_EMPTY;
            }
                                    
            // 5.5 完成 Runtime Metrics
            long totalTime = System.currentTimeMillis() - startTime;
            agentMetricsService.finishMetrics(playlistId, totalTime, true, null);
            circuitBreakerService.recordSuccess();
                        
            // 6. 更新播放列表状态
            if (context.getPlaylistId() != null) {
                int playlistTargetCount = context.getIntent().getTargetCount();
                int actualCount = context.getSelectedVideos().size();
                boolean isPartial = playlistTargetCount > 0 && actualCount < playlistTargetCount;
                
                databaseService.finishPlaylist(context.getPlaylistId(), isPartial);
                log.info("[Database] 播放列表状态已更新: {}", isPartial ? "PARTIAL" : "DONE");
            }
            
            // 7. 清理上下文（任务完成）
            contextPersistenceService.deleteContext(playlistId);
            
            log.info("=".repeat(60));
            log.info("[PlaylistAgent] 任务完成");
            log.info("=".repeat(60));
            statusCallback.accept("✅ 歌单生成完成");
                        
            // 8. 构建响应
            return buildResponse(context, metrics, errorCode);
            
        } catch (Exception e) {
            log.error("[PlaylistAgent] 任务执行失败: playlistId={}", playlistId, e);
            statusCallback.accept("❌ 任务执行失败: " + e.getMessage());
        
            // 记录失败 Metrics
            agentMetricsService.finishMetrics(playlistId, 0L, false, e.getMessage());
            circuitBreakerService.recordFailure(e);
        
            // 根据异常类型细化错误码
            AgentErrorCode errorCode = resolveErrorCodeFromException(e);
        
            return PlaylistResponse.builder()
                .videos(Collections.emptyList())
                .summary("任务执行失败: " + e.getMessage())
                .trashVideos(Collections.emptyList())
                .mp3Files(Collections.emptyList())
                .errorCode(errorCode.name())
                .build();
                
        } finally {
            // 释放锁
            executionLockService.unlock(playlistId);
        }
    }
    
    /**
     * Debug 模式：从指定快照恢复并重跑状态机
     */
    public PlaylistResponse debugReplay(Long playlistId, String executionId, int step, String stopAtNode, Consumer<String> statusCallback) {
        log.info("[PlaylistAgent][DebugReplay] 从快照恢复并重跑: playlistId={}, executionId={}, step={}",
            playlistId, executionId, step);

        // 为避免干扰正常执行，仍然尝试获取执行锁
        if (!executionLockService.tryLock(playlistId)) {
            log.warn("[PlaylistAgent][DebugReplay] 播放列表正在执行中，无法重跑: playlistId={}", playlistId);
            statusCallback.accept("⚠️ 该播放列表正在执行中，暂不支持同时 Debug 重跑");
            return PlaylistResponse.builder()
                .videos(Collections.emptyList())
                .summary("该播放列表正在执行中，暂不支持 Debug 重跑")
                .trashVideos(Collections.emptyList())
                .mp3Files(Collections.emptyList())
                .errorCode(AgentErrorCode.PLAYLIST_LOCKED.name())
                .build();
        }

        try {
            // 从节点快照恢复上下文
            PlaylistContext context = contextPersistenceService.loadNodeSnapshot(playlistId, executionId, step);
            if (context == null) {
                log.warn("[PlaylistAgent][DebugReplay] 未找到节点快照: playlistId={}, executionId={}, step={}",
                    playlistId, executionId, step);
                statusCallback.accept("❌ 未找到指定的快照，无法重跑");
                return PlaylistResponse.builder()
                    .videos(Collections.emptyList())
                    .summary("未找到指定的快照，无法重跑")
                    .trashVideos(Collections.emptyList())
                    .mp3Files(Collections.emptyList())
                    .build();
            }
        
            Long conversationId = context.getConversationId();

            // 基于快照中的 intent.mode 构造一个最小的请求，用于策略选择
            PlaylistRequest replayRequest = null;
            UserIntent intent = context.getIntent();
            if (intent != null && intent.getMode() != null) {
                replayRequest = new PlaylistRequest();
                replayRequest.setMode(intent.getMode());
            }

            // 构建状态图（基于原始模式选择策略）
            PlaylistAgentGraph graph = graphBuilder.build(replayRequest);
            String strategy = graph.getPolicyName();

            if (stopAtNode != null && !stopAtNode.isBlank()) {
                graph.setDebugStopNodeName(stopAtNode);
            }

            // 初始化 Runtime Metrics（附带策略信息，便于 A/B 分析）
            agentMetricsService.getOrCreateMetrics(playlistId, conversationId, strategy);
            long startTime = System.currentTimeMillis();

            // 执行图（会继续在每个节点后保存快照和执行追踪）
            statusCallback.accept("🎯 开始 Debug 重跑状态机...");
            executeWithPersistence(graph, context);

            // 计算并记录指标
            ExecutionTrace trace = graph.getExecutionTrace();
            ExecutionMetrics metrics = metricsService.calculateMetrics(trace, context, strategy);
            metricsService.recordMetrics(metrics);

            // 完成 Runtime Metrics
            long totalTime = System.currentTimeMillis() - startTime;
            agentMetricsService.finishMetrics(playlistId, totalTime, true, null);
        
            statusCallback.accept("✅ Debug 重跑完成");
            return buildResponse(context, metrics, null);
        } catch (Exception e) {
            log.error("[PlaylistAgent][DebugReplay] 重跑失败: playlistId={}", playlistId, e);
            statusCallback.accept("❌ Debug 重跑失败: " + e.getMessage());
            agentMetricsService.finishMetrics(playlistId, 0L, false, e.getMessage());
            circuitBreakerService.recordFailure(e);
        
            AgentErrorCode errorCode = resolveErrorCodeFromException(e);
        
            return PlaylistResponse.builder()
                .videos(Collections.emptyList())
                .summary("Debug 重跑失败: " + e.getMessage())
                .trashVideos(Collections.emptyList())
                .mp3Files(Collections.emptyList())
                .errorCode(errorCode.name())
                .build();
        } finally {
            executionLockService.unlock(playlistId);
        }
    }
    
    /**
     * 初始化或恢复 Context（断点续跑）
     */
    private PlaylistContext initOrRestoreContext(PlaylistRequest request, Long playlistId, Long conversationId, Long userId) {
        // 尝试从 Redis 恢复未完成的上下文
        PlaylistContext context = contextPersistenceService.loadContext(playlistId);
        
        if (context != null) {
            log.info("[PlaylistAgent] 检测到未完成任务，从断点恢复: stage={}", context.getCurrentStage());
            return context;
        }
        
        // 未找到上下文，初始化新的
        log.info("[PlaylistAgent] 初始化新的执行上下文");
        context = initContext(request);
        context.setConversationId(conversationId);
        context.setUserId(userId);
        context.setPlaylistId(playlistId);
        
        // 保存初始上下文
        contextPersistenceService.saveContext(playlistId, context);
        
        return context;
    }
    
    /**
     * 初始化 Context
     */
    private PlaylistContext initContext(PlaylistRequest request) {
        PlaylistContext context = new PlaylistContext();
        
        // targetCount = 0 表示不限制数量，返回所有搜索结果
        int targetCount = request.getLimit();
        // 搜索视频数量：有目标时 *2，无目标时默认搜索50个
        int videoLimit = targetCount > 0 ? Math.max(targetCount * 2, 20) : 50;

        UserIntent intent = UserIntent.builder()
            .query(request.getQuery())
            .targetCount(targetCount)
            .limit(videoLimit)
            .preference(request.getPreference())
            .downloadAsMp3(request.isDownloadAsMp3())
            .mode(request.getMode())
            .build();
        
        context.setIntent(intent);
        context.setCurrentStage(PlaylistContext.Stage.INIT);
        
        return context;
    }
    
    /**
     * 执行图并定期保存上下文（用于断点续跑）
     */
    private void executeWithPersistence(PlaylistAgentGraph graph, PlaylistContext context) {
        Long playlistId = context.getPlaylistId();
        
        // 执行前保存
        contextPersistenceService.saveContext(playlistId, context);
        
        try {
            graph.setNodeBeforeHook((nodeName, state) -> {
                String executionId = graph.getExecutionTrace() != null ? graph.getExecutionTrace().getExecutionId() : null;
                AgentTraceContext.set(new AgentTraceContext.Context(
                    state != null ? state.getPlaylistId() : null,
                    state != null ? state.getConversationId() : null,
                    executionId,
                    nodeName
                ));
            });
            graph.setNodeAfterHook((nodeName, state, result) -> AgentTraceContext.clear());

            // 执行状态图
            graph.execute(context);
        } finally {
            AgentTraceContext.clear();
            // 执行后保存（无论成功或失败）
            contextPersistenceService.updateContext(playlistId, context);
        }
    }
    
    private AgentErrorCode resolveErrorCodeFromException(Exception e) {
        if (e == null) {
            return AgentErrorCode.INTERNAL_ERROR;
        }
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            String lower = msg != null ? msg.toLowerCase() : "";
            if (cause instanceof java.util.concurrent.TimeoutException || lower.contains("timeout")) {
                return AgentErrorCode.LLM_TIMEOUT;
            }
            if (lower.contains("llm 调用失败") || lower.contains("llm 调用超时")
                    || lower.contains("llm timeout") || lower.contains("llm failed")) {
                return AgentErrorCode.LLM_ERROR;
            }
            cause = cause.getCause();
        }
        return AgentErrorCode.INTERNAL_ERROR;
    }

    /**
     * 构建响应（流式模式下只返回摘要和垃圾桶候选，不返回视频列表）
     */
    private PlaylistResponse buildResponse(PlaylistContext context, ExecutionMetrics metrics, AgentErrorCode errorCode) {
        Double confidence = null;
        if (metrics != null) {
            Double hitRate = metrics.getHitRate();
            Double acceptanceRate = metrics.getAcceptanceRate();
            Double completionRate = metrics.getTargetCompletionRate();
            double score = 0.0;
            if (hitRate != null) {
                score += hitRate * 0.5;
            }
            if (acceptanceRate != null) {
                score += acceptanceRate * 0.3;
            }
            if (completionRate != null) {
                score += completionRate * 0.2;
            }
            if (score < 0.0) {
                score = 0.0;
            } else if (score > 1.0) {
                score = 1.0;
            }
            confidence = score;
        }
        
        // 生成推荐结果解释
        PlaylistResponse.RecommendationExplanation explanation = null;
        try {
            explanation = explanationService.generateExplanation(
                context, context.getSelectedVideos());
        } catch (Exception e) {
            log.warn("[推荐解释] 生成失败", e);
        }

        return PlaylistResponse.builder()
            .videos(Collections.emptyList())  // 不再返回视频列表
            .summary(context.getSummary())
            .trashVideos(context.getTrashVideos())
            .mp3Files(Collections.emptyList())
            .confidence(confidence)
            .explanation(explanation)
            .errorCode(errorCode != null ? errorCode.name() : null)
            .build();
    }

    /**
     * 导出当前策略下的状态图结构（用于可视化）。
     *
     * @param mode 可选的模式标签，用于选择不同策略
     * @return 状态图的文本描述（可在前端转换为图形）
     */
    public String visualizeGraph(String mode) {
        PlaylistRequest request = new PlaylistRequest();
        request.setQuery("__visualize__");
        request.setLimit(10);
        if (mode != null && !mode.isBlank()) {
            request.setMode(mode);
        }
        PlaylistAgentGraph graph = graphBuilder.build(request);
        return graph.visualize();
    }
    
}
