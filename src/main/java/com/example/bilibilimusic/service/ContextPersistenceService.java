package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.AgentState;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.ExecutionTrace;
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
    // 节点快照 TTL（同样 24 小时，主要用于调试回放）
    private static final long SNAPSHOT_TTL_HOURS = 24;
    
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
     * 节点级快照：在每个 Agent Node 执行后保存一次核心状态
     * 
     * 用途：
     * 1. Debug Replay：根据 executionId + step 回放执行过程
     * 2. 手动 Resume：从某个节点快照恢复 AgentState 后重新执行图
     */
    public void saveNodeSnapshot(Long playlistId, String executionId, int step, PlaylistContext context) {
        try {
            if (playlistId == null || executionId == null) {
                return;
            }
            String key = getSnapshotKey(playlistId, executionId, step);
            String json = objectMapper.writeValueAsString(context.getState());
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(json, SNAPSHOT_TTL_HOURS, TimeUnit.HOURS);
            log.debug("[ContextPersist] 保存节点快照: playlistId={}, executionId={}, step={}, stage={}",
                playlistId, executionId, step, context.getCurrentStage());
        } catch (JsonProcessingException e) {
            log.error("[ContextPersist] 序列化节点快照失败: playlistId={}, executionId={}, step={}",
                playlistId, executionId, step, e);
        }
    }
    
    /**
     * 加载节点快照（用于 Debug Replay）
     */
    public PlaylistContext loadNodeSnapshot(Long playlistId, String executionId, int step) {
        try {
            if (playlistId == null || executionId == null) {
                return null;
            }
            String key = getSnapshotKey(playlistId, executionId, step);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null) {
                log.debug("[ContextPersist] 未找到节点快照: playlistId={}, executionId={}, step={}",
                    playlistId, executionId, step);
                return null;
            }
            AgentState state = objectMapper.readValue(json, AgentState.class);
            PlaylistContext context = new PlaylistContext();
            context.setState(state);
            return context;
        } catch (Exception e) {
            log.error("[ContextPersist] 加载节点快照失败: playlistId={}, executionId={}, step={}",
                playlistId, executionId, step, e);
            return null;
        }
    }

    /**
     * 保存完整执行追踪（用于 Debug Replay）
     */
    public void saveExecutionTrace(ExecutionTrace trace) {
        if (trace == null || trace.getPlaylistId() == null || trace.getExecutionId() == null) {
            return;
        }
        try {
            String key = getExecutionTraceKey(trace.getPlaylistId(), trace.getExecutionId());
            String json = objectMapper.writeValueAsString(trace);
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(json, SNAPSHOT_TTL_HOURS, TimeUnit.HOURS);
            log.debug("[ContextPersist] 保存执行追踪: playlistId={}, executionId={}",
                trace.getPlaylistId(), trace.getExecutionId());
        } catch (JsonProcessingException e) {
            log.error("[ContextPersist] 序列化执行追踪失败: playlistId={}, executionId={}",
                trace.getPlaylistId(), trace.getExecutionId(), e);
        }
    }

    /**
     * 加载完整执行追踪
     */
    public ExecutionTrace loadExecutionTrace(Long playlistId, String executionId) {
        try {
            if (playlistId == null || executionId == null) {
                return null;
            }
            String key = getExecutionTraceKey(playlistId, executionId);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null) {
                log.debug("[ContextPersist] 未找到执行追踪: playlistId={}, executionId={}",
                    playlistId, executionId);
                return null;
            }
            return objectMapper.readValue(json, ExecutionTrace.class);
        } catch (Exception e) {
            log.error("[ContextPersist] 加载执行追踪失败: playlistId={}, executionId={}",
                playlistId, executionId, e);
            return null;
        }
    }

    /**
     * 生成上下文 Key
     */
    private String getContextKey(Long playlistId) {
        return "agent:context:" + playlistId;
    }
    
    /**
     * 生成节点快照 Key
     */
    private String getSnapshotKey(Long playlistId, String executionId, int step) {
        return "agent:snapshot:" + playlistId + ":" + executionId + ":" + step;
    }
    
    /**
     * 生成执行追踪 Key
     */
    private String getExecutionTraceKey(Long playlistId, String executionId) {
        return "agent:trace:" + playlistId + ":" + executionId;
    }
}