package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.skill.KeywordExtractionSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词提取节点（集成缓存）
 */
@Slf4j
@RequiredArgsConstructor
public class KeywordExtractionNode implements AgentNode {
    
    private final KeywordExtractionSkill keywordExtractionSkill;
    private final SimpMessagingTemplate messagingTemplate;
    private final CacheService cacheService;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        log.info("[KeywordNode] 开始提取关键词");
        
        state.setCurrentStage(PlaylistContext.Stage.KEYWORD_EXTRACTION);
        
        String query = state.getIntent().getQuery();
        
        // 尝试从缓存获取关键词
        List<String> cachedKeywords = cacheService.getCachedKeywords(query);
        
        if (cachedKeywords != null && !cachedKeywords.isEmpty()) {
            log.info("[KeywordNode] 命中关键词缓存: {}", cachedKeywords);
            state.getIntent().setKeywords(cachedKeywords);
        } else {
            // 缓存未命中，调用Skill提取关键词
            keywordExtractionSkill.execute(state);
            
            // 缓存提取结果
            List<String> keywords = state.getIntent().getKeywords();
            if (keywords != null && !keywords.isEmpty()) {
                cacheService.cacheKeywords(query, keywords);
            }
        }
        
        // 推送关键词提取结果
        pushKeywordUpdate(state);
        
        log.info("[KeywordNode] 关键词提取完成: {}", state.getIntent().getKeywords());
        return NodeResult.success("video_retrieval");
    }
    
    private void pushKeywordUpdate(PlaylistContext context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("keywords", context.getIntent().getKeywords());
        payload.put("effectiveQuery", context.getIntent().getQuery());
    
        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stage_update")
            .stage("KEYWORD_EXTRACTION")
            .content("💬 已将需求拆解为关键词")
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }
}
