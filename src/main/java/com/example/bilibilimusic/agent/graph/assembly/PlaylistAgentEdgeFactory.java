package com.example.bilibilimusic.agent.graph.assembly;

import com.example.bilibilimusic.agent.graph.ConditionalEdge;
import com.example.bilibilimusic.agent.graph.definition.GraphDefinition;

public interface PlaylistAgentEdgeFactory {
    ConditionalEdge create(GraphDefinition.EdgeDefinition edgeDefinition);
}

