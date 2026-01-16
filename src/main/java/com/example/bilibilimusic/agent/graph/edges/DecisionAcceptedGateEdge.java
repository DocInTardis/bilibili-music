package com.example.bilibilimusic.agent.graph.edges;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.agent.graph.ConditionalEdge;
import com.example.bilibilimusic.context.PlaylistContext;

/**
 * relevance_decision -> video_accepted / progress_update
 */
public class DecisionAcceptedGateEdge implements ConditionalEdge {

    @Override
    public String decide(PlaylistContext state, AgentNode.NodeResult lastResult) {
        if (state != null && state.getLastDecisionInfo() != null) {
            Object acceptedObj = state.getLastDecisionInfo().get("accepted");
            if (acceptedObj instanceof Boolean accepted && accepted) {
                return "video_accepted";
            }
        }
        return "progress_update";
    }

    @Override
    public String getConditionExpression() {
        return "lastDecision.accepted ? video_accepted : progress_update";
    }
}

