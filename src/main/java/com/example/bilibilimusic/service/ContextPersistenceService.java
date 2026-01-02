package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.AgentState;
import com.example.bilibilimusic.context.PlaylistContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Agent 执行上下文持久化服务（优化版）
 * 
 * 功能：
 * 1. 断点续跑：Agent 重启后恢复上次执行状态
 * 2. 状态快照：定期保存执行进度
 * 3. 异常恢复：捕获异常前保存现场
 * 
 * 优化：
 * - 只持久化 AgentState（核心状态）
 * - WorkingMemory/ExecutionControl/StreamingState 不持久化
 * - 减少 Redis 存储开销，提高序列化效率
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContextPersistenceService {
    
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    
    // 上下文 TTL（24小时）
    private static final long CONTEXT_TTL_HOURS = 24;
    
    /**
     * 保存执行上下文（只持久化 AgentState）
     */
    public void saveContext(Long playlistId, PlaylistContext context) {
        try {
            String key = getContextKey(playlistId);
            // 只序列化 AgentState，不序列化 WorkingMemory
            String json = objectMapper.writeValueAsString(context.getState());
            
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(json, CONTEXT_TTL_HOURS, TimeUnit.HOURS);
            
            log.debug("[ContextPersist] 保存执行上下文: playlistId={}, stage={}", 
                playlistId, context.getCurrentStage());
        } catch (JsonProcessingException e) {
            log.error("[ContextPersist] 序列化上下文失败: playlistId={}", playlistId, e);
        }
    }
    
    /**
     * 加载执行上下文（用于断点续跑）
     * 
     * 注意：只恢复 AgentState，WorkingMemory 需要重新生成
     */
    public PlaylistContext loadContext(Long playlistId) {
        try {
            String key = getContextKey(playlistId);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            
            if (json == null) {
                log.debug("[ContextPersist] 未找到上下文: playlistId={}", playlistId);
                return null;
            }
            
            // 反序列化 AgentState
            AgentState state = objectMapper.readValue(json, AgentState.class);
            
            // 重建 PlaylistContext
            PlaylistContext context = new PlaylistContext();
            context.setState(state);
            
            log.info("[ContextPersist] 加载执行上下文: playlistId={}, stage={}", 
                playlistId, context.getCurrentStage());
            
            return context;
        } catch (Exception e) {
            log.error("[ContextPersist] 反序列化上下文失败: playlistId={}", playlistId, e);
            return null;
        }
    }
    
    /**
     * 删除执行上下文（完成后清理）
     */
    public void deleteContext(Long playlistId) {
        String key = getContextKey(playlistId);
        RBucket<String> bucket = redissonClient.getBucket(key);
        bucket.delete();
        log.debug("[ContextPersist] 删除执行上下文: playlistId={}", playlistId);
    }
    
    /**
     * 检查是否存在未完成的上下文
     */
    public boolean hasUnfinishedContext(Long playlistId) {
        String key = getContextKey(playlistId);
        RBucket<String> bucket = redissonClient.getBucket(key);
        return bucket.isExists();
    }
    
    /**
     * 更新上下文（增量保存）
     */
    public void updateContext(Long playlistId, PlaylistContext context) {
        // 与 saveContext 相同，但语义上表示更新
        saveContext(playlistId, context);
    }
    
    /**
     * 生成上下文 Key
     */
    private String getContextKey(Long playlistId) {
        return "agent:context:" + playlistId;
    }
}
