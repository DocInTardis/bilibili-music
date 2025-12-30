package com.example.bilibilimusic.agent.graph.edges;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.agent.graph.ConditionalEdge;
import com.example.bilibilimusic.context.PlaylistContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 检索后条件边
 * 
 * 决策逻辑：
 * - 如果有搜索结果 -> judge_video
 * - 如果无搜索结果 -> null（结束）
 */
@Slf4j
public class AfterRetrievalEdge implements ConditionalEdge {
    
    @Override
    public String decide(PlaylistContext state, AgentNode.NodeResult lastResult) {
        if (!lastResult.isSuccess()) {
            log.warn("[AfterRetrievalEdge] 检索失败，结束流程");
            return null;
        }
        
        if (state.getSearchResults().isEmpty()) {
            log.warn("[AfterRetrievalEdge] 无搜索结果，结束流程");
            return null;
        }
        
        log.info("[AfterRetrievalEdge] 找到 {} 个视频，开始预排序并进入判断循环", state.getSearchResults().size());
        return "pre_sort_videos";
    }
}
