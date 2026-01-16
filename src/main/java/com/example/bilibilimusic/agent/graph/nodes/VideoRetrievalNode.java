package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.websocket.WsTopicPublisher;
import com.example.bilibilimusic.skill.RetrievalSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频检索节点（集成缓存）
 */
@Slf4j
@RequiredArgsConstructor
public class VideoRetrievalNode implements AgentNode {
    
    private final RetrievalSkill retrievalSkill;
    private final WsTopicPublisher wsTopicPublisher;
    private final CacheService cacheService;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        log.info("[RetrievalNode] 开始检索视频");
        
        state.setCurrentStage(PlaylistContext.Stage.VIDEO_RETRIEVAL);
        
        String query = state.getIntent().getQuery();
        
        // 尝试从缓存获取搜索结果
        List<VideoInfo> cachedResults = cacheService.getCachedSearchResults(query);
        
        if (cachedResults != null && !cachedResults.isEmpty()) {
            log.info("[RetrievalNode] 命中搜索缓存，视频数: {}", cachedResults.size());
            state.setSearchResults(cachedResults);
        } else {
            // 缓存未命中，调用Skill检索
            boolean success = retrievalSkill.execute(state);
            
            if (!success || state.getSearchResults().isEmpty()) {
                log.warn("[RetrievalNode] 检索失败或无结果");
                return NodeResult.failure("no_results");
            }
            
            // 缓存搜索结果
            cacheService.cacheSearchResults(query, state.getSearchResults());
        }
        
        // 推送搜索结果
        pushSearchResults(state);
        
        log.info("[RetrievalNode] 检索成功，找到 {} 个视频", state.getSearchResults().size());
        return NodeResult.success();
    }
    
    private void pushSearchResults(PlaylistContext context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("totalCount", context.getSearchResults().size());
        
        // 前5个样本
        if (!context.getSearchResults().isEmpty()) {
            var samples = new ArrayList<Map<String, String>>();
            for (int i = 0; i < Math.min(5, context.getSearchResults().size()); i++) {
                var v = context.getSearchResults().get(i);
                Map<String, String> sample = new HashMap<>();
                sample.put("title", v.getTitle());
                sample.put("author", v.getAuthor());
                sample.put("duration", v.getDuration());
                samples.add(sample);
            }
            payload.put("samples", samples);
        }
        
        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("search_results")
            .stage("VIDEO_RETRIEVAL")
            .content(String.format("🔍 搜索到 %d 个视频", context.getSearchResults().size()))
            .payload(payload)
            .build();
        wsTopicPublisher.send("/topic/messages", msg);
    }
}
