package com.example.bilibilimusic.agent.graph.assembly;

import com.example.bilibilimusic.agent.graph.PlaylistAgentGraph;
import com.example.bilibilimusic.agent.graph.definition.GraphDefinition;
import com.example.bilibilimusic.agent.graph.definition.GraphDefinitionLoader;
import org.springframework.stereotype.Service;

@Service
public class GraphAssemblyService {

    private final GraphDefinitionLoader loader;
    private final PlaylistAgentNodeFactory nodeFactory;
    private final PlaylistAgentEdgeFactory edgeFactory;

    public GraphAssemblyService(GraphDefinitionLoader loader,
                                PlaylistAgentNodeFactory nodeFactory,
                                PlaylistAgentEdgeFactory edgeFactory) {
        this.loader = loader;
        this.nodeFactory = nodeFactory;
        this.edgeFactory = edgeFactory;
    }

    public void assemble(PlaylistAgentGraph graph, String resourceLocation) {
        GraphDefinition def = loader.load(resourceLocation);
        if (def.getGraphVersion() != null && !def.getGraphVersion().isBlank()) {
            graph.setGraphVersion(def.getGraphVersion());
        }

        if (def.getNodes() != null) {
            for (GraphDefinition.NodeDefinition node : def.getNodes()) {
                graph.addNode(node.getId(), nodeFactory.create(node.getType()));
            }
        }

        if (def.getEdges() != null) {
            for (GraphDefinition.EdgeDefinition edge : def.getEdges()) {
                graph.addEdge(edge.getFrom(), edgeFactory.create(edge));
            }
        }

        graph.setStart(def.getStart());
    }
}

