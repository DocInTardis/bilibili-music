package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.agent.graph.edges.AfterRetrievalEdge;
import com.example.bilibilimusic.agent.graph.edges.ContinueJudgeEdge;
import com.example.bilibilimusic.agent.graph.nodes.*;
import com.example.bilibilimusic.service.AgentBehaviorLogService;
import com.example.bilibilimusic.service.AgentMetricsService;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.DatabaseService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.skill.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * PlaylistAgent 图构建器
 * 
 * 负责组装状态机的所有节点和边
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaylistAgentGraphBuilder {
    
    private final KeywordExtractionSkill keywordExtractionSkill;
    private final RetrievalSkill retrievalSkill;
    private final CurationSkill curationSkill;
    private final VideoRelevanceScorer relevanceScorer;
    private final SummarySkill summarySkill;
    private final SimpMessagingTemplate messagingTemplate;
    private final DatabaseService databaseService;
    private final UserPreferenceService preferenceService;
    private final CacheService cacheService;
    private final AgentBehaviorLogService behaviorLogService;
    private final AgentMetricsService metricsService;
    
    /**
     * 构建 PlaylistAgent 状态图
     */
    public PlaylistAgentGraph build() {
        PlaylistAgentGraph graph = new PlaylistAgentGraph(behaviorLogService, metricsService);
        
        // 1. 添加所有节点
        graph.addNode("intent_understanding", 
            new IntentUnderstandingNode(messagingTemplate));
        
        graph.addNode("keyword_extraction", 
            new KeywordExtractionNode(keywordExtractionSkill, messagingTemplate, cacheService));
        
        graph.addNode("video_retrieval", 
            new VideoRetrievalNode(retrievalSkill, messagingTemplate, cacheService));
        
        // Video Judgement Loop 子图节点
        graph.addNode("pre_sort_videos", 
            new PreSortVideosNode(preferenceService, cacheService));
        graph.addNode("content_analysis", 
            new ContentAnalysisNode(messagingTemplate));
        graph.addNode("quantity_estimation", 
            new QuantityEstimationNode());
        graph.addNode("relevance_decision", 
            new RelevanceDecisionNode(relevanceScorer, preferenceService, cacheService));
        graph.addNode("video_accepted", 
            new VideoAcceptedNode(databaseService, messagingTemplate));
        graph.addNode("progress_update", 
            new ProgressUpdateNode(messagingTemplate));
        graph.addNode("loop_control", 
            new LoopControlNode());
        
        graph.addNode("target_evaluation", 
            new TargetEvaluationNode(messagingTemplate));
        
        graph.addNode("generate_summary", 
            new GenerateSummaryNode(summarySkill, messagingTemplate));
        
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
        
        log.info("[GraphBuilder] PlaylistAgent 状态图构建完成");
        log.debug("[GraphBuilder] 图结构:\n{}", graph.visualize());
        
        return graph;
    }
}
