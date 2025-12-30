package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.skill.KeywordExtractionSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 关键词提取节点
 */
@Slf4j
@RequiredArgsConstructor
public class KeywordExtractionNode implements AgentNode {
    
    private final KeywordExtractionSkill keywordExtractionSkill;
    private final SimpMessagingTemplate messagingTemplate;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        log.info("[KeywordNode] 开始提取关键词");
        
        state.setCurrentStage(PlaylistContext.Stage.KEYWORD_EXTRACTION);
        
        // 调用Skill提取关键词
        keywordExtractionSkill.execute(state);
        
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
