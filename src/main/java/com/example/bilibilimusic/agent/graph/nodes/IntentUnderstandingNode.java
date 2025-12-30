package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图理解节点
 * 
 * 职责：
 * - 理解用户需求
 * - 初始化意图结构
 * - 设置Stage
 */
@Slf4j
@RequiredArgsConstructor
public class IntentUnderstandingNode implements AgentNode {
    
    private final SimpMessagingTemplate messagingTemplate;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        log.info("[IntentNode] 开始理解用户意图");
        
        // 设置当前阶段
        state.setCurrentStage(PlaylistContext.Stage.INTENT_UNDERSTANDING);
        
        // 推送状态更新
        pushIntentUpdate(state);
        
        log.info("[IntentNode] 意图理解完成");
        return NodeResult.success("keyword_extraction");
    }
    
    private void pushIntentUpdate(PlaylistContext context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", context.getIntent().getQuery());
        payload.put("targetCount", context.getIntent().getTargetCount());
        payload.put("scenario", context.getIntent().getScenario());
        payload.put("preference", context.getIntent().getPreference());

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stage_update")
            .stage("INTENT_UNDERSTANDING")
            .content("🎯 已理解你的需求")
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }
}
