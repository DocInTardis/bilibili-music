package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 目标评估节点
 * 
 * 职责：
 * - 评估是否满足用户目标
 * - 生成与原 runVideoJudgementLoop 等价的选择理由与阶段推送
 */
@Slf4j
@RequiredArgsConstructor
public class TargetEvaluationNode implements AgentNode {
    
    private final SimpMessagingTemplate messagingTemplate;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        log.info("[EvalNode] 开始评估目标完成情况");
        
        state.setCurrentStage(PlaylistContext.Stage.TARGET_EVALUATION);
        
        int targetCount = state.getIntent().getTargetCount();
        int finalCount = state.getAccumulatedCount();
        int trashCount = state.getTrashVideos().size();

        // targetCount = 0 表示不限制，这时认为有结果就满足
        boolean enough = (targetCount == 0 && finalCount > 0)
            || (targetCount > 0 && finalCount >= targetCount);

        log.info("[EvalNode] 目标: {}, 实际估算歌曲数: {}, enough={}", targetCount, finalCount, enough);

        // 设置 selectionReason，与原 runVideoJudgementLoop 保持一致
        if (!enough) {
            state.setCurrentStage(PlaylistContext.Stage.PARTIAL_RESULT);
            state.setSelectionReason(String.format(
                "仅找到约 %d 首，未达到目标 %d 首，已返回部分结果和相关推荐。",
                finalCount, targetCount));
        } else {
            String reason = targetCount == 0
                ? String.format("基于视频标题和时长估算，共收集约 %d 首歌曲。", finalCount)
                : String.format("基于视频标题和时长估算，共收集约 %d 首歌曲，满足你的需求。", finalCount);
            state.setSelectionReason(reason);
        }

        // 推送评估结果
        pushEvaluationResult(state, targetCount, finalCount, trashCount, enough);
        
        return NodeResult.success("generate_summary");
    }
    
    private void pushEvaluationResult(PlaylistContext context, int targetCount, int finalCount, int trashCount, boolean enough) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetCount", targetCount);
        payload.put("actualCount", finalCount);
        payload.put("enough", enough);
        payload.put("trashCount", trashCount);

        String content;
        if (enough) {
            content = (targetCount == 0)
                ? "已返回所有搜索结果"
                : "已基本满足目标数量";
        } else {
            content = "未完全达到目标数量，将返回部分结果和相关推荐";
        }

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stage_update")
            .stage("TARGET_EVALUATION")
            .content(content)
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }
}
