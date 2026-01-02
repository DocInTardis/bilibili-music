package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.EdgeTrace;
import com.example.bilibilimusic.dto.ExecutionTrace;
import com.example.bilibilimusic.dto.NodeTrace;
import com.example.bilibilimusic.service.AgentBehaviorLogService;
import com.example.bilibilimusic.service.AgentMetricsService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PlaylistAgent 的状态图
 * 
 * 将原有的过程式控制流重构为状态驱动的图结构
 */
@Slf4j
@RequiredArgsConstructor
public class PlaylistAgentGraph {
    
    private final AgentBehaviorLogService behaviorLogService;
    private final AgentMetricsService metricsService;
    
    private final Map<String, AgentNode> nodes = new HashMap<>();
    private final Map<String, ConditionalEdge> edges = new HashMap<>();
    private String startNode;
    
    /**
     * 执行追踪记录
     */
    @Getter
    private ExecutionTrace executionTrace;
    
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
        
        // 初始化执行追踪
        executionTrace = ExecutionTrace.builder()
            .executionId(UUID.randomUUID().toString())
            .conversationId(state.getConversationId())
            .playlistId(state.getPlaylistId())
            .startTime(System.currentTimeMillis())
            .status("RUNNING")
            .build();
        
        String currentNode = startNode;
        AgentNode.NodeResult lastResult = null;
        int maxIterations = 1000; // 防止无限循环
        int iterations = 0;
        
        try {
            while (currentNode != null && iterations < maxIterations) {
                iterations++;
                
                log.debug("[Graph] 执行节点: {}", currentNode);
                
                // 记录行为日志：节点进入
                behaviorLogService.logNodeEnter(
                    state.getPlaylistId(), 
                    state.getConversationId(), 
                    currentNode
                );
                
                // 执行当前节点
                AgentNode node = nodes.get(currentNode);
                if (node == null) {
                    log.error("[Graph] 节点不存在: {}", currentNode);
                    break;
                }
                
                // 记录节点开始时间
                long nodeStartTime = System.currentTimeMillis();
                
                try {
                    lastResult = node.execute(state);
                    
                    // 记录节点执行追踪
                    long nodeEndTime = System.currentTimeMillis();
                    long nodeDuration = nodeEndTime - nodeStartTime;
                    
                    NodeTrace nodeTrace = NodeTrace.builder()
                        .nodeName(currentNode)
                        .startTime(nodeStartTime)
                        .endTime(nodeEndTime)
                        .durationMs(nodeDuration)
                        .success(true)
                        .output(lastResult != null ? lastResult.getNextNode() : null)
                        .build();
                    executionTrace.addNodeTrace(nodeTrace);
                    
                    // 记录行为日志：节点退出
                    behaviorLogService.logNodeExit(
                        state.getPlaylistId(), 
                        state.getConversationId(), 
                        currentNode, 
                        nodeDuration, 
                        true, 
                        null
                    );
                    
                    // 记录 Metrics：节点执行
                    metricsService.recordNodeExecution(
                        state.getPlaylistId(), 
                        currentNode, 
                        nodeDuration
                    );
                    
                } catch (Exception e) {
                    // 记录节点失败
                    long nodeEndTime = System.currentTimeMillis();
                    long nodeDuration = nodeEndTime - nodeStartTime;
                    
                    NodeTrace nodeTrace = NodeTrace.builder()
                        .nodeName(currentNode)
                        .startTime(nodeStartTime)
                        .endTime(nodeEndTime)
                        .durationMs(nodeDuration)
                        .success(false)
                        .error(e.getMessage())
                        .build();
                    executionTrace.addNodeTrace(nodeTrace);
                    
                    // 记录行为日志：错误
                    behaviorLogService.logError(
                        state.getPlaylistId(), 
                        state.getConversationId(), 
                        currentNode,
                        e.getMessage(),
                        getStackTrace(e)
                    );
                    
                    // 记录行为日志：节点退出（失败）
                    behaviorLogService.logNodeExit(
                        state.getPlaylistId(), 
                        state.getConversationId(), 
                        currentNode, 
                        nodeDuration, 
                        false, 
                        e.getMessage()
                    );
                    
                    log.error("[Graph] 节点执行失败: {}", currentNode, e);
                    executionTrace.setStatus("FAILED");
                    throw e;
                }
                
                // 根据条件边决定下一个节点
                ConditionalEdge edge = edges.get(currentNode);
                if (edge == null) {
                    // 没有边，说明是终止节点
                    log.debug("[Graph] 节点 {} 没有出边，执行结束", currentNode);
                    break;
                }
                
                String nextNode = edge.decide(state, lastResult);
                
                // 记录边决策追踪
                if (nextNode != null) {
                    boolean isLoop = nextNode.equals("content_analysis"); // 判断是否是循环边
                    EdgeTrace edgeTrace = EdgeTrace.builder()
                        .fromNode(currentNode)
                        .toNode(nextNode)
                        .timestamp(System.currentTimeMillis())
                        .reason("Conditional decision")
                        .isLoop(isLoop)
                        .build();
                    executionTrace.addEdgeTrace(edgeTrace);
                    
                    // 记录行为日志：边转移
                    behaviorLogService.logEdgeTransition(
                        state.getPlaylistId(), 
                        state.getConversationId(), 
                        "conditional_edge",
                        currentNode, 
                        nextNode
                    );
                }
                
                if (nextNode == null) {
                    log.debug("[Graph] 条件边返回null，执行结束");
                    break;
                }
                
                log.debug("[Graph] 从 {} -> {}", currentNode, nextNode);
                currentNode = nextNode;
            }
            
            if (iterations >= maxIterations) {
                log.error("[Graph] 达到最大迭代次数，可能存在无限循环");
                executionTrace.setStatus("TIMEOUT");
            } else {
                executionTrace.setStatus("SUCCESS");
            }
            
        } finally {
            // 完成执行追踪
            long endTime = System.currentTimeMillis();
            executionTrace.setEndTime(endTime);
            executionTrace.setTotalDurationMs(endTime - executionTrace.getStartTime());
            
            log.info("[Graph] 图执行完成，共执行 {} 个节点，总耗时: {}ms", iterations, executionTrace.getTotalDurationMs());
            log.info("[Graph] {}", executionTrace.getSummary());
        }
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
    
    /**
     * 获取异常堆栈信息
     */
    private String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
            // 只取前 10 行堆栈
            if (sb.length() > 1000) {
                sb.append("\t...\n");
                break;
            }
        }
        return sb.toString();
    }
}
