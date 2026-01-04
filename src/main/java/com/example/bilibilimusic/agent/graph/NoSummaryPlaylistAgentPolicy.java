package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.agent.graph.edges.AfterRetrievalEdge;
import com.example.bilibilimusic.agent.graph.edges.ContinueJudgeEdge;
import com.example.bilibilimusic.agent.graph.nodes.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 无摘要场景策略：
 * - 图结构与默认策略基本一致，但在目标评估后直接结束，不再进入 generate_summary 节点。
 * - 适用于“纯流式播放”“前端自己总结”等不需要 Agent 生成摘要的场景。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoSummaryPlaylistAgentPolicy implements PlaylistAgentPolicy {

    @Override
    public void configure(PlaylistAgentGraph graph, PlaylistAgentGraphBuilder builder) {
        // 1. 添加所有节点（除了 generate_summary）
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

        // 2. 添加条件边

        // intent_understanding -> keyword_extraction（固定边）
        graph.addEdge("intent_understanding",
            (state, result) -> "keyword_extraction");

        // keyword_extraction -> video_retrieval（固定边）
        graph.addEdge("keyword_extraction",
            (state, result) -> "video_retrieval");

        // video_retrieval -> pre_sort_videos / END
        graph.addEdge("video_retrieval",
            new AfterRetrievalEdge());

        // pre_sort_videos -> content_analysis（固定边）
        graph.addEdge("pre_sort_videos",
            (state, result) -> "content_analysis");

        // content_analysis -> quantity_estimation / progress_update（Conditional Edge：不可理解直接进入流式反馈）
        graph.addEdge("content_analysis",
            (state, result) -> {
                if (!state.isCurrentUnderstandable()) {
                    return "progress_update";
                }
                return "quantity_estimation";
            });

        // quantity_estimation -> relevance_decision
        graph.addEdge("quantity_estimation",
            (state, result) -> "relevance_decision");

        // relevance_decision -> video_accepted / progress_update（条件边：如果 accepted 则先执行持久化）
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

        // video_accepted -> progress_update（固定边）
        graph.addEdge("video_accepted",
            (state, result) -> "progress_update");

        // progress_update -> loop_control（固定边）
        graph.addEdge("progress_update",
            (state, result) -> "loop_control");

        // loop_control -> content_analysis / target_evaluation（循环边）
        graph.addEdge("loop_control",
            new ContinueJudgeEdge());

        // 注意：这里不再添加 target_evaluation -> generate_summary 的边
        // target_evaluation 成为终止节点，执行结束后直接返回，形成“无摘要”流程。

        // 3. 设置起始节点
        graph.setStart("intent_understanding");

        log.info("[GraphPolicy-NoSummary] 无摘要状态图构建完成");
        log.debug("[GraphPolicy-NoSummary] 图结构:\n{}", graph.visualize());
    }
}
