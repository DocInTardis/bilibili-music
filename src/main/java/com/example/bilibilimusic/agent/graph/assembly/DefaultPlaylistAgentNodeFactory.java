package com.example.bilibilimusic.agent.graph.assembly;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.agent.graph.nodes.*;
import com.example.bilibilimusic.config.AgentPrefetchConfig;
import com.example.bilibilimusic.service.AudioFingerprintService;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.DatabaseService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.service.telemetry.DecisionTelemetryService;
import com.example.bilibilimusic.service.websocket.WsTopicPublisher;
import com.example.bilibilimusic.skill.KeywordExtractionSkill;
import com.example.bilibilimusic.skill.RetrievalSkill;
import com.example.bilibilimusic.skill.SummarySkill;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class DefaultPlaylistAgentNodeFactory implements PlaylistAgentNodeFactory {

    private final Map<String, Supplier<AgentNode>> registry;

    public DefaultPlaylistAgentNodeFactory(KeywordExtractionSkill keywordExtractionSkill,
                                          RetrievalSkill retrievalSkill,
                                          SummarySkill summarySkill,
                                          WsTopicPublisher wsTopicPublisher,
                                          DatabaseService databaseService,
                                          UserPreferenceService preferenceService,
                                          CacheService cacheService,
                                          VideoRelevanceScorer relevanceScorer,
                                          AudioFingerprintService audioFingerprintService,
                                          DecisionTelemetryService decisionTelemetryService,
                                          AgentPrefetchConfig agentPrefetchConfig) {
        Map<String, Supplier<AgentNode>> map = new HashMap<>();
        map.put("intent_understanding", () -> new IntentUnderstandingNode(wsTopicPublisher));
        map.put("keyword_extraction", () -> new KeywordExtractionNode(keywordExtractionSkill, wsTopicPublisher, cacheService));
        map.put("video_retrieval", () -> new VideoRetrievalNode(retrievalSkill, wsTopicPublisher, cacheService));
        map.put("pre_sort_videos", () -> new PreSortVideosNode(preferenceService, cacheService, relevanceScorer, agentPrefetchConfig));
        map.put("content_analysis", () -> new ContentAnalysisNode(wsTopicPublisher, decisionTelemetryService));
        map.put("quantity_estimation", () -> new QuantityEstimationNode(audioFingerprintService));
        map.put("relevance_decision", () -> new RelevanceDecisionNode(relevanceScorer, preferenceService, cacheService, decisionTelemetryService));
        map.put("video_accepted", () -> new VideoAcceptedNode(databaseService, wsTopicPublisher));
        map.put("progress_update", () -> new ProgressUpdateNode(wsTopicPublisher));
        map.put("loop_control", LoopControlNode::new);
        map.put("target_evaluation", () -> new TargetEvaluationNode(wsTopicPublisher));
        map.put("generate_summary", () -> new GenerateSummaryNode(summarySkill, wsTopicPublisher));
        this.registry = Map.copyOf(map);
    }

    @Override
    public AgentNode create(String type) {
        Supplier<AgentNode> supplier = registry.get(type);
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown node type: " + type);
        }
        return supplier.get();
    }
}
