package com.example.bilibilimusic.agent.graph.edges;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.agent.graph.ConditionalEdge;
import com.example.bilibilimusic.context.PlaylistContext;

public class FixedEdge implements ConditionalEdge {

    private final String to;

    public FixedEdge(String to) {
        this.to = to;
    }

    @Override
    public String decide(PlaylistContext state, AgentNode.NodeResult lastResult) {
        return to;
    }

    @Override
    public String getConditionExpression() {
        return "fixed -> " + (to != null ? to : "<END>");
    }
}

