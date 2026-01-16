package com.example.bilibilimusic.agent.graph.assembly;

import com.example.bilibilimusic.agent.graph.ConditionalEdge;
import com.example.bilibilimusic.agent.graph.definition.GraphDefinition;
import com.example.bilibilimusic.agent.graph.edges.AfterRetrievalEdge;
import com.example.bilibilimusic.agent.graph.edges.ContinueJudgeEdge;
import com.example.bilibilimusic.agent.graph.edges.DecisionAcceptedGateEdge;
import com.example.bilibilimusic.agent.graph.edges.ContentAnalysisGateEdge;
import com.example.bilibilimusic.agent.graph.edges.FixedEdge;
import org.springframework.stereotype.Component;

@Component
public class DefaultPlaylistAgentEdgeFactory implements PlaylistAgentEdgeFactory {

    @Override
    public ConditionalEdge create(GraphDefinition.EdgeDefinition edgeDefinition) {
        if (edgeDefinition == null || edgeDefinition.getType() == null) {
            throw new IllegalArgumentException("Edge type is required");
        }
        return switch (edgeDefinition.getType()) {
            case "fixed" -> new FixedEdge(edgeDefinition.getTo());
            case "after_retrieval" -> new AfterRetrievalEdge();
            case "continue_judge" -> new ContinueJudgeEdge();
            case "content_analysis_gate" -> new ContentAnalysisGateEdge();
            case "decision_accepted_gate" -> new DecisionAcceptedGateEdge();
            default -> throw new IllegalArgumentException("Unknown edge type: " + edgeDefinition.getType());
        };
    }
}

