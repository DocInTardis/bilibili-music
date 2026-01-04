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
                        
            // 4. 执行图（定期保存上下文）
            statusCallback.accept("🎯 开始执行状态机...");
            executeWithPersistence(graph, context);
            
            // 5. 计算并记录指标
            ExecutionTrace trace = graph.getExecutionTrace();
            ExecutionMetrics metrics = metricsService.calculateMetrics(trace, context, strategy);
            metricsService.recordMetrics(metrics);
                        
            // 5.5 完成 Runtime Metrics
            long totalTime = System.currentTimeMillis() - startTime;
            agentMetricsService.finishMetrics(playlistId, totalTime, true, null);
            
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
            return buildResponse(context);
            
        } catch (Exception e) {
            log.error("[PlaylistAgent] 任务执行失败: playlistId={}", playlistId, e);
            statusCallback.accept("❌ 任务执行失败: " + e.getMessage());
            
            // 记录失败 Metrics
            agentMetricsService.finishMetrics(playlistId, 0L, false, e.getMessage());
            
            return PlaylistResponse.builder()
                .videos(Collections.emptyList())
                .summary("任务执行失败: " + e.getMessage())
                .trashVideos(Collections.emptyList())
                .mp3Files(Collections.emptyList())
                .build();
                
        } finally {
            // 释放锁
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
            // 执行状态图
            graph.execute(context);
        } finally {
            // 执行后保存（无论成功或失败）
            contextPersistenceService.updateContext(playlistId, context);
        }
    }
    
    /**
     * 视频逐个判断循环：内容分析 + 数量估算 + 采纳决策 + 流式反馈
     */
    // 已完全由状态机节点替代，保留方法签名已无必要，故删除

    /**
     * 构建响应（流式模式下只返回摘要和垃圾桶候选，不返回视频列表）
     */
    private PlaylistResponse buildResponse(PlaylistContext context) {
        // 流式模式：视频已经通过 WebSocket 逐个发送，这里只返回空列表
        return PlaylistResponse.builder()
            .videos(Collections.emptyList())  // 不再返回视频列表
            .summary(context.getSummary())
            .trashVideos(context.getTrashVideos())
            .mp3Files(Collections.emptyList())
            .build();
    }
    
}
