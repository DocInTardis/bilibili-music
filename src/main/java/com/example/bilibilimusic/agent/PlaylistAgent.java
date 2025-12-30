package com.example.bilibilimusic.agent;

import com.example.bilibilimusic.agent.graph.PlaylistAgentGraph;
import com.example.bilibilimusic.agent.graph.PlaylistAgentGraphBuilder;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.entity.Conversation;
import com.example.bilibilimusic.entity.Playlist;
import com.example.bilibilimusic.service.DatabaseService;
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
    
    /**
     * 执行歌单生成任务（使用状态机）
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
            
        int targetCount = request.getLimit();
        Playlist playlist = databaseService.createPlaylist(
            conversationId, 
            request.getQuery(), 
            targetCount
        );
        Long playlistId = playlist.getId();
            
        log.info("[Database] 会话ID: {}, 播放列表ID: {}", conversationId, playlistId);
            
        // 1. 初始化 Context
        PlaylistContext context = initContext(request);
        context.setConversationId(conversationId);
        context.setPlaylistId(playlistId);
            
        // 2. 构建状态图
        PlaylistAgentGraph graph = graphBuilder.build();
            
        // 3. 执行图
        statusCallback.accept("🎯 开始执行状态机...");
        graph.execute(context);
            
        // 4. 更新播放列表状态
        if (context.getPlaylistId() != null) {
            int playlistTargetCount = context.getIntent().getTargetCount();
            int actualCount = context.getSelectedVideos().size();
            boolean isPartial = playlistTargetCount > 0 && actualCount < playlistTargetCount;
                
            databaseService.finishPlaylist(context.getPlaylistId(), isPartial);
            log.info("[Database] 播放列表状态已更新: {}", isPartial ? "PARTIAL" : "DONE");
        }
            
        log.info("=".repeat(60));
        log.info("[PlaylistAgent] 任务完成");
        log.info("=".repeat(60));
        statusCallback.accept("✅ 歌单生成完成");
            
        // 5. 构建响应
        return buildResponse(context);
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
            .build();
        
        context.setIntent(intent);
        context.setCurrentStage(PlaylistContext.Stage.INIT);
        
        return context;
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
