package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.EdgeTrace;
import com.example.bilibilimusic.dto.ExecutionTrace;
import com.example.bilibilimusic.dto.NodeTrace;
import com.example.bilibilimusic.service.AgentBehaviorLogService;
import com.example.bilibilimusic.service.AgentMetricsService;
import com.example.bilibilimusic.service.ContextPersistenceService;
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
    private final ContextPersistenceService contextPersistenceService;
    
    // 最大执行时长（毫秒），用于防止整体执行时间过长
    private long maxDurationMs = 60_000L;
    
    // 节点级失败重试次数（不含首次尝试），默认重试 1 次
    private int maxNodeRetries = 1;

    // 节点级重试覆盖配置：key 为节点名称，value 为该节点的最大重试次数（不含首次尝试）
    private final Map<String, Integer> nodeRetryOverrides = new HashMap<>();
        
    /**
     * Debug 模式下的停止节点名称（命中后立即停止执行）
     */
    private String debugStopNodeName;
    
    public void setDebugStopNodeName(String debugStopNodeName) {
        this.debugStopNodeName = debugStopNodeName;
    }
        
    /**
     * 本次执行使用的策略名（Policy），用于 A/B 分析
     */
    @Getter
    private String policyName;
        
    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }
    
    public void setMaxDurationMs(long maxDurationMs) {
        if (maxDurationMs > 0) {
            this.maxDurationMs = maxDurationMs;
        }
    }
    
    public void setMaxNodeRetries(int maxNodeRetries) {
        if (maxNodeRetries >= 0) {
            this.maxNodeRetries = maxNodeRetries;
        }
    }

    public void setNodeRetryOverrides(Map<String, Integer> overrides) {
        if (overrides == null) {
            return;
        }
        this.nodeRetryOverrides.clear();
        this.nodeRetryOverrides.putAll(overrides);
    }
        
    private final Map<String, AgentNode> nodes = new HashMap<>();
    private final Map<String, ConditionalEdge> edges = new HashMap<>();
    private String startNode;
    
    /**
     * 执行追踪记录
     */
    @Getter
    private ExecutionTrace executionTrace;

    /**
     * 图执行状态（显式状态机），用于细粒度可观测性
     */
    public enum ExecutionState {
        INIT,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    @Getter
    private ExecutionState executionState = ExecutionState.INIT;

    /**
     * Node 执行前 Hook（可选）。
     */
    @FunctionalInterface
    public interface NodeBeforeHook {
        void apply(String nodeName, PlaylistContext state);
    }

    /**
     * Node 执行后 Hook（可选），可拿到节点执行结果。
     */
    @FunctionalInterface
    public interface NodeAfterHook {
        void apply(String nodeName, PlaylistContext state, AgentNode.NodeResult result);
    }

    private NodeBeforeHook nodeBeforeHook;
    private NodeAfterHook nodeAfterHook;

    public void setNodeBeforeHook(NodeBeforeHook hook) {
        this.nodeBeforeHook = hook;
    }

    public void setNodeAfterHook(NodeAfterHook hook) {
        this.nodeAfterHook = hook;
    }
    
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

        executionState = ExecutionState.RUNNING;
        
        String currentNode = startNode;
        AgentNode.NodeResult lastResult = null;
        int maxIterations = 1000; // 防止无限循环
        int iterations = 0;
        
        try {
            while (currentNode != null && iterations < maxIterations) {
                iterations++;
                
                // 检查全局执行时长
                long elapsed = System.currentTimeMillis() - executionTrace.getStartTime();
                if (elapsed > maxDurationMs) {
                    log.error("[Graph] 达到最大执行时长 {} ms，提前结束执行", maxDurationMs);
                    executionTrace.setStatus("TIMEOUT");
                    executionState = ExecutionState.TIMEOUT;
                    break;
                }
                
                log.debug("[Graph] 执行节点: {}", currentNode);

                // Node 执行前 Hook
                // 便于统一做埋点、熔断检查等横切逻辑
                if (nodeBeforeHook != null) {
                    try {
                        nodeBeforeHook.apply(currentNode, state);
                    } catch (Exception hookEx) {
                        log.warn("[Graph] nodeBeforeHook 执行异常: {}", hookEx.getMessage());
                    }
                }
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
                
                int attempt = 0;
                boolean nodeSucceeded = false;
                int maxRetriesForNode = nodeRetryOverrides.getOrDefault(currentNode, maxNodeRetries);
                while (!nodeSucceeded) {
                    attempt++;
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

                        // Node 执行后 Hook
                        if (nodeAfterHook != null) {
                            try {
                                nodeAfterHook.apply(currentNode, state, lastResult);
                            } catch (Exception hookEx) {
                                log.warn("[Graph] nodeAfterHook 执行异常: {}", hookEx.getMessage());
                            }
                        }
                        
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
                        
                        // 在每个节点成功执行后保存一次核心状态快照（支持回放与断点分析）
                        int step = executionTrace.getNodeTraces() != null ? executionTrace.getNodeTraces().size() : 0;
                        contextPersistenceService.saveNodeSnapshot(
                            state.getPlaylistId(),
                            executionTrace.getExecutionId(),
                            step,
                            state
                        );
                        nodeSucceeded = true;
                    } catch (Exception e) {
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
                        
                        log.error("[Graph] 节点执行失败: {} (attempt={})", currentNode, attempt, e);
                        if (attempt <= maxRetriesForNode) {
                            log.warn("[Graph] 准备重试节点: {} (第 {}/{} 次)", currentNode, attempt, maxRetriesForNode + 1);
                            continue;
                        }
                        executionTrace.setStatus("FAILED");
                        executionState = ExecutionState.FAILED;
                        throw e;
                    }
                }
                
                // Debug 模式：如果命中停止节点，则提前终止执行
                if (debugStopNodeName != null && debugStopNodeName.equals(currentNode)) {
                    log.info("[Graph][Debug] 命中停止节点 {}，提前结束执行", currentNode);
                    break;
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
                        .reason(edge.getConditionExpression())
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

                // 应用可能的策略热切换请求
                if (state != null && state.getControl() != null) {
                    state.getControl().applyStrategySwitchIfNeeded();
                    String newStrategy = state.getControl().getStrategyName();
                    if (newStrategy != null && !newStrategy.equals(policyName)) {
                        log.info("[Graph] 策略热切换: {} -> {}", policyName, newStrategy);
                        policyName = newStrategy;
                    }
                }
            }
            
            if (executionTrace.getStatus() == null || "RUNNING".equals(executionTrace.getStatus())) {
                if (iterations >= maxIterations) {
                    log.error("[Graph] 达到最大迭代次数，可能存在无限循环");
                    executionTrace.setStatus("TIMEOUT");
                    executionState = ExecutionState.TIMEOUT;
                } else if (!"TIMEOUT".equals(executionTrace.getStatus()) && !"FAILED".equals(executionTrace.getStatus())) {
                    executionTrace.setStatus("SUCCESS");
                    executionState = ExecutionState.COMPLETED;
                }
            }
            
        } finally {
            // 完成执行追踪
            long endTime = System.currentTimeMillis();
            executionTrace.setEndTime(endTime);
            executionTrace.setTotalDurationMs(endTime - executionTrace.getStartTime());
            executionTrace.setFsmState(executionState != null ? executionState.name() : null);
            executionTrace.setContextVersion(state != null ? state.getContextVersion() : null);
            executionTrace.setGraphVersion(policyName);
            
            // 持久化完整执行追踪，配合节点快照用于 Debug Replay
            try {
                contextPersistenceService.saveExecutionTrace(executionTrace);
            } catch (Exception e) {
                log.warn("[Graph] 保存执行追踪失败: {}", e.getMessage());
            }
            
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
