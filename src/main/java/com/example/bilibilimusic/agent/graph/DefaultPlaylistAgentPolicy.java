package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.agent.graph.edges.AfterRetrievalEdge;
import com.example.bilibilimusic.agent.graph.edges.ContinueJudgeEdge;
import com.example.bilibilimusic.agent.graph.nodes.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认的 PlaylistAgent 策略实现，对应当前单轮歌单生成流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPlaylistAgentPolicy implements PlaylistAgentPolicy {

    @Override
    public void configure(PlaylistAgentGraph graph, PlaylistAgentGraphBuilder builder) {
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

        // target_evaluation -> generate_summary（固定边）
        graph.addEdge("target_evaluation",
            (state, result) -> "generate_summary");

        // generate_summary -> END（终止节点，无边）
        // 不添加边，返回null表示结束

        // 3. 设置起始节点
        graph.setStart("intent_understanding");

        log.info("[GraphPolicy] PlaylistAgent 默认状态图构建完成");
        log.debug("[GraphPolicy] 图结构:\n{}", graph.visualize());
    }
}
