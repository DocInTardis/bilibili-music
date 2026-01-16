package com.example.bilibilimusic.agent.graph.definition;

import lombok.Data;

import java.util.List;

@Data
public class GraphDefinition {
    private String graphVersion;
    private String start;
    private List<NodeDefinition> nodes;
    private List<EdgeDefinition> edges;

    @Data
    public static class NodeDefinition {
        private String id;
        private String type;
    }

    @Data
    public static class EdgeDefinition {
        private String from;
        private String type;
        private String to;
    }
}

