package com.example.bilibilimusic.agent.graph.edges;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.agent.graph.ConditionalEdge;
import com.example.bilibilimusic.context.PlaylistContext;

/**
 * content_analysis -> quantity_estimation / progress_update
 */
public class ContentAnalysisGateEdge implements ConditionalEdge {

    @Override
    public String decide(PlaylistContext state, AgentNode.NodeResult lastResult) {
        if (state != null && !state.isCurrentUnderstandable()) {
            return "progress_update";
        }
        return "quantity_estimation";
    }

    @Override
    public String getConditionExpression() {
        return "!currentUnderstandable ? progress_update : quantity_estimation";
    }
}

