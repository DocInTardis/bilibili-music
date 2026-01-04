package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.agent.graph.edges.AfterRetrievalEdge;
import com.example.bilibilimusic.agent.graph.edges.ContinueJudgeEdge;
import com.example.bilibilimusic.agent.graph.nodes.*;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.service.AgentBehaviorLogService;
import com.example.bilibilimusic.service.AgentMetricsService;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.ContextPersistenceService;
import com.example.bilibilimusic.service.DatabaseService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.skill.*;
import lombok.Getter;
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
@Getter
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
    private final ContextPersistenceService contextPersistenceService;
    private final PlaylistAgentPolicySelector policySelector;
    
    /**
     * 构建 PlaylistAgent 状态图（根据请求选择策略）
     */
    public PlaylistAgentGraph build(PlaylistRequest request) {
        PlaylistAgentGraph graph = new PlaylistAgentGraph(behaviorLogService, metricsService, contextPersistenceService);
                
        PlaylistAgentPolicy policy = policySelector.selectPolicy(request);
        graph.setPolicyName(policy.getClass().getSimpleName());
        policy.configure(graph, this);
                
        return graph;
    }
    
    /**
     * 使用默认策略构建 PlaylistAgent 状态图
     */
    public PlaylistAgentGraph build() {
        return build(null);
    }
}
