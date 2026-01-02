package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.agent.graph.edges.AfterRetrievalEdge;
import com.example.bilibilimusic.agent.graph.edges.ContinueJudgeEdge;
import com.example.bilibilimusic.agent.graph.nodes.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 低成本模式策略：
 * - 使用与默认策略相同的节点与边拓扑
 * - 通过 UserIntent.mode = "low_cost" 配合各 Skill 内部的降级逻辑，减少 LLM 调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowCostPlaylistAgentPolicy implements PlaylistAgentPolicy {

    @Override
    public void configure(PlaylistAgentGraph graph, PlaylistAgentGraphBuilder builder) {
        // 与默认策略保持相同的图结构，区别由 Skill 内根据 mode=low_cost 走降级路径实现

        // 1. 添加所有节点
        graph.addNode("intent_understanding",
            new IntentUnderstandingNode(builder.getMessagingTemplate()));

        graph.addNode("keyword_extraction",
            new KeywordExtractionNode(builder.getKeywordExtractionSkill(), builder.getMessagingTemplate(), builder.getCacheService()));

        graph.addNode("video_retrieval",
            new VideoRetrievalNode(builder.getRetrievalSkill(), builder.getMessagingTemplate(), builder.getCacheService()));

        // Video Judgement Loop 子图节点
        graph.addNode("pre_sort_videos",
            new PreSortVideosNode(builder.getPreferenceService(), builder.getCacheService()));
        graph.addNode("content_analysis",
            new ContentAnalysisNode(builder.getMessagingTemplate()));
        graph.addNode("quantity_estimation",
            new QuantityEstimationNode());
        graph.addNode("relevance_decision",
            new RelevanceDecisionNode(builder.getRelevanceScorer(), builder.getPreferenceService(), builder.getCacheService()));
        graph.addNode("video_accepted",
            new VideoAcceptedNode(builder.getDatabaseService(), builder.getMessagingTemplate()));
        graph.addNode("progress_update",
            new ProgressUpdateNode(builder.getMessagingTemplate()));
        graph.addNode("loop_control",
            new LoopControlNode());

        graph.addNode("target_evaluation",
            new TargetEvaluationNode(builder.getMessagingTemplate()));

        graph.addNode("generate_summary",
            new GenerateSummaryNode(builder.getSummarySkill(), builder.getMessagingTemplate()));

        // 2. 添加条件边（与默认策略一致）
        graph.addEdge("intent_understanding",
            (state, result) -> "keyword_extraction");

        graph.addEdge("keyword_extraction",
            (state, result) -> "video_retrieval");

        graph.addEdge("video_retrieval",
            new AfterRetrievalEdge());

        graph.addEdge("pre_sort_videos",
            (state, result) -> "content_analysis");

        graph.addEdge("content_analysis",
            (state, result) -> {
                if (!state.isCurrentUnderstandable()) {
                    return "progress_update";
                }
                return "quantity_estimation";
            });

        graph.addEdge("quantity_estimation",
            (state, result) -> "relevance_decision");

        graph.addEdge("relevance_decision",
            (state, result) -> {
                if (state.getLastDecisionInfo() != null) {
                    Object acceptedObj = state.getLastDecisionInfo().get("accepted");
                    if (acceptedObj instanceof Boolean && (Boolean) acceptedObj) {
                        return "video_accepted";
                    }
                }
                return "progress_update";
            });

        graph.addEdge("video_accepted",
            (state, result) -> "progress_update");

        graph.addEdge("progress_update",
            (state, result) -> "loop_control");

        graph.addEdge("loop_control",
            new ContinueJudgeEdge());

        graph.addEdge("target_evaluation",
            (state, result) -> "generate_summary");

        // generate_summary -> END（终止节点，无边）

        log.info("[GraphPolicy-LowCost] 低成本状态图构建完成");
        log.debug("[GraphPolicy-LowCost] 图结构:\n{}", graph.visualize());
    }
}
