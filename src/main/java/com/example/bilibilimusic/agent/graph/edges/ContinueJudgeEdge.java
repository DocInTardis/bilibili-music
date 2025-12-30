package com.example.bilibilimusic.agent.graph.edges;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.agent.graph.ConditionalEdge;
import com.example.bilibilimusic.context.PlaylistContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 继续判断条件边（循环控制）
 * 
 * 决策逻辑：
 * - 如果shouldContinue=true 且未达标 -> judge_video（回环）
 * - 否则 -> target_evaluation（跳出循环）
 */
@Slf4j
public class ContinueJudgeEdge implements ConditionalEdge {
    
    @Override
    public String decide(PlaylistContext state, AgentNode.NodeResult lastResult) {
        // 检查是否应该继续
        if (state.isShouldContinue() && !state.isTargetReached()) {
            // 检查是否还有未处理的视频
            if (state.getCurrentVideoIndex() < state.getSearchResults().size()) {
                log.debug("[ContinueJudgeEdge] 继续判断，当前进度: {}/{}", 
                    state.getCurrentVideoIndex(), state.getSearchResults().size());
                return "content_analysis";  // 回环
            }
        }
        
        // 跳出循环，进入评估阶段
        log.info("[ContinueJudgeEdge] 判断结束，进入评估阶段");
        return "target_evaluation";
    }
}
