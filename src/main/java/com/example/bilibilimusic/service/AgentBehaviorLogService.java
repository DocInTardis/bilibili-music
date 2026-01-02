package com.example.bilibilimusic.service;

import com.example.bilibilimusic.entity.AgentBehaviorLog;
import com.example.bilibilimusic.mapper.AgentBehaviorLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Agent 行为日志服务
 * 
 * 用于记录 Agent 执行的所有行为，支持：
 * 1. 调试：追踪每一步执行
 * 2. 可视化：展示执行路径
 * 3. 评估：性能分析和优化
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentBehaviorLogService {
    
    private final AgentBehaviorLogMapper behaviorLogMapper;
    private final ObjectMapper objectMapper;
    
    /**
     * 记录节点进入
     */
    public void logNodeEnter(Long playlistId, Long conversationId, String nodeName) {
        try {
            AgentBehaviorLog log = AgentBehaviorLog.builder()
                .playlistId(playlistId)
                .conversationId(conversationId)
                .behaviorType("NODE_ENTER")
                .nodeName(nodeName)
                .description("进入节点: " + nodeName)
                .success(true)
                .createdAt(LocalDateTime.now())
                .build();
            
            behaviorLogMapper.insert(log);
        } catch (Exception e) {
            log.error("[BehaviorLog] 记录节点进入失败: nodeName={}", nodeName, e);
        }
    }
    
    /**
     * 记录节点退出
     */
    public void logNodeExit(Long playlistId, Long conversationId, String nodeName, 
                           long durationMs, boolean success, String errorMessage) {
        try {
            AgentBehaviorLog log = AgentBehaviorLog.builder()
                .playlistId(playlistId)
                .conversationId(conversationId)
                .behaviorType("NODE_EXIT")
                .nodeName(nodeName)
                .description("退出节点: " + nodeName)
                .durationMs(durationMs)
                .success(success)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
            
            behaviorLogMapper.insert(log);
        } catch (Exception e) {
            log.error("[BehaviorLog] 记录节点退出失败: nodeName={}", nodeName, e);
        }
    }
    
    /**
     * 记录边转移
     */
    public void logEdgeTransition(Long playlistId, Long conversationId, String edgeName,
                                  String sourceNode, String targetNode) {
        try {
            AgentBehaviorLog log = AgentBehaviorLog.builder()
                .playlistId(playlistId)
                .conversationId(conversationId)
                .behaviorType("EDGE_TRANSITION")
                .edgeName(edgeName)
                .sourceNode(sourceNode)
                .targetNode(targetNode)
                .description(String.format("边转移: %s -> %s (%s)", sourceNode, targetNode, edgeName))
                .success(true)
                .createdAt(LocalDateTime.now())
                .build();
            
            behaviorLogMapper.insert(log);
        } catch (Exception e) {
            log.error("[BehaviorLog] 记录边转移失败: edge={}", edgeName, e);
        }
    }
    
    /**
     * 记录 LLM 调用
     */
    public void logLLMCall(Long playlistId, Long conversationId, String nodeName,
                          String promptVersion, Object input, Object output, 
                          long durationMs, boolean success) {
        try {
            String inputJson = input != null ? objectMapper.writeValueAsString(input) : null;
            String outputJson = output != null ? objectMapper.writeValueAsString(output) : null;
            
            AgentBehaviorLog log = AgentBehaviorLog.builder()
                .playlistId(playlistId)
                .conversationId(conversationId)
                .behaviorType("LLM_CALL")
                .nodeName(nodeName)
                .description("LLM 调用: " + nodeName)
                .promptVersion(promptVersion)
                .inputData(inputJson)
                .outputData(outputJson)
                .durationMs(durationMs)
                .success(success)
                .createdAt(LocalDateTime.now())
                .build();
            
            behaviorLogMapper.insert(log);
        } catch (Exception e) {
            log.error("[BehaviorLog] 记录 LLM 调用失败: nodeName={}", nodeName, e);
        }
    }
    
    /**
     * 记录缓存命中
     */
    public void logCacheHit(Long playlistId, Long conversationId, String nodeName, 
                           String cacheKey, String description) {
        try {
            AgentBehaviorLog log = AgentBehaviorLog.builder()
                .playlistId(playlistId)
                .conversationId(conversationId)
                .behaviorType("CACHE_HIT")
                .nodeName(nodeName)
                .description(description != null ? description : "缓存命中: " + cacheKey)
                .inputData(cacheKey)
                .success(true)
                .createdAt(LocalDateTime.now())
                .build();
            
            behaviorLogMapper.insert(log);
        } catch (Exception e) {
            log.error("[BehaviorLog] 记录缓存命中失败: nodeName={}", nodeName, e);
        }
    }
    
    /**
     * 记录错误
     */
    public void logError(Long playlistId, Long conversationId, String nodeName,
                        String errorMessage, String stackTrace) {
        try {
            AgentBehaviorLog log = AgentBehaviorLog.builder()
                .playlistId(playlistId)
                .conversationId(conversationId)
                .behaviorType("ERROR")
                .nodeName(nodeName)
                .description("执行错误: " + nodeName)
                .errorMessage(errorMessage)
                .outputData(stackTrace)
                .success(false)
                .createdAt(LocalDateTime.now())
                .build();
            
            behaviorLogMapper.insert(log);
        } catch (Exception e) {
            log.error("[BehaviorLog] 记录错误失败: nodeName={}", nodeName, e);
        }
    }
}
