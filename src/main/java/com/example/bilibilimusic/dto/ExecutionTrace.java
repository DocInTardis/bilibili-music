package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整执行追踪
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionTrace {
    
    /**
     * 执行ID（可用于关联到具体会话/播放列表）
     */
    private String executionId;
    
    /**
     * 会话ID
     */
    private Long conversationId;
    
    /**
     * 播放列表ID
     */
    private Long playlistId;
    
    /**
     * 执行开始时间
     */
    private Long startTime;
    
    /**
     * 执行结束时间
     */
    private Long endTime;
    
    /**
     * 总耗时（毫秒）
     */
    private Long totalDurationMs;
    
    /**
     * 节点追踪列表
     */
    @Builder.Default
    private List<NodeTrace> nodeTraces = new ArrayList<>();
    
    /**
     * 边追踪列表
     */
    @Builder.Default
    private List<EdgeTrace> edgeTraces = new ArrayList<>();
    
    /**
     * 执行状态
     */
    private String status; // SUCCESS / FAILED / TIMEOUT
    
    /**
     * 添加节点追踪
     */
    public void addNodeTrace(NodeTrace trace) {
        if (nodeTraces == null) {
            nodeTraces = new ArrayList<>();
        }
        nodeTraces.add(trace);
    }
    
    /**
     * 添加边追踪
     */
    public void addEdgeTrace(EdgeTrace trace) {
        if (edgeTraces == null) {
            edgeTraces = new ArrayList<>();
        }
        edgeTraces.add(trace);
    }
    
    /**
     * 获取执行摘要
     */
    public String getSummary() {
        int nodeCount = nodeTraces != null ? nodeTraces.size() : 0;
        int edgeCount = edgeTraces != null ? edgeTraces.size() : 0;
        return String.format("执行: %d个节点, %d次跳转, 总耗时%dms", 
            nodeCount, edgeCount, totalDurationMs);
    }
}
