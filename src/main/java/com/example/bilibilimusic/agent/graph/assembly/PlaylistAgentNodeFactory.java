package com.example.bilibilimusic.agent.graph.assembly;

import com.example.bilibilimusic.agent.graph.AgentNode;

public interface PlaylistAgentNodeFactory {
    AgentNode create(String type);
}

