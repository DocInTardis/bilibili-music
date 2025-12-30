package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.context.PlaylistContext;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * PlaylistAgent 的状态图
 * 
 * 将原有的过程式控制流重构为状态驱动的图结构
 */
@Slf4j
public class PlaylistAgentGraph {
    
    private final Map<String, AgentNode> nodes = new HashMap<>();
    private final Map<String, ConditionalEdge> edges = new HashMap<>();
    private String startNode;
    
    /**
     * 添加节点
     */
    public PlaylistAgentGraph addNode(String name, AgentNode node) {
        nodes.put(name, node);
        return this;
    }
    
    /**
     * 添加条件边
     */
    public PlaylistAgentGraph addEdge(String fromNode, ConditionalEdge edge) {
        edges.put(fromNode, edge);
        return this;
    }
    
    /**
     * 设置起始节点
     */
    public PlaylistAgentGraph setStart(String nodeName) {
        this.startNode = nodeName;
        return this;
    }
    
    /**
     * 执行图
     */
    public void execute(PlaylistContext state) {
        if (startNode == null) {
            throw new IllegalStateException("起始节点未设置");
        }
        
        String currentNode = startNode;
        AgentNode.NodeResult lastResult = null;
        int maxIterations = 1000; // 防止无限循环
        int iterations = 0;
        
        while (currentNode != null && iterations < maxIterations) {
            iterations++;
            
            log.debug("[Graph] 执行节点: {}", currentNode);
            
            // 执行当前节点
            AgentNode node = nodes.get(currentNode);
            if (node == null) {
                log.error("[Graph] 节点不存在: {}", currentNode);
                break;
            }
            
            lastResult = node.execute(state);
            
            // 根据条件边决定下一个节点
            ConditionalEdge edge = edges.get(currentNode);
            if (edge == null) {
                // 没有边，说明是终止节点
                log.debug("[Graph] 节点 {} 没有出边，执行结束", currentNode);
                break;
            }
            
            String nextNode = edge.decide(state, lastResult);
            
            if (nextNode == null) {
                log.debug("[Graph] 条件边返回null，执行结束");
                break;
            }
            
            log.debug("[Graph] 从 {} -> {}", currentNode, nextNode);
            currentNode = nextNode;
        }
        
        if (iterations >= maxIterations) {
            log.error("[Graph] 达到最大迭代次数，可能存在无限循环");
        }
        
        log.info("[Graph] 图执行完成，共执行 {} 个节点", iterations);
    }
    
    /**
     * 获取图的可视化表示（用于调试）
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("PlaylistAgent 状态图:\n");
        sb.append("起始节点: ").append(startNode).append("\n\n");
        sb.append("节点列表:\n");
        for (String nodeName : nodes.keySet()) {
            sb.append("  - ").append(nodeName).append("\n");
        }
        sb.append("\n边列表:\n");
        for (String from : edges.keySet()) {
            sb.append("  ").append(from).append(" -> [条件边]\n");
        }
        return sb.toString();
    }
}
