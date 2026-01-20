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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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

    @Value("${REDIS_ENABLED:true}")
    private boolean redisEnabled;

    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean(false);
    
    // 上下文 TTL（24小时）
    private static final long CONTEXT_TTL_HOURS = 24;
    // 节点快照 TTL（同样 24 小时，主要用于调试回放）
    private static final long SNAPSHOT_TTL_HOURS = 24;
    
    /**
     * 保存执行上下文（只持久化 AgentState）
     */
    public void saveContext(Long playlistId, PlaylistContext context) {
        if (!isRedisUsable()) {
            return;
        }
        try {
            String key = getContextKey(playlistId);
            SnapshotWrapper wrapper = new SnapshotWrapper();
            wrapper.contextVersion = context != null ? context.getContextVersion() : PlaylistContext.CONTEXT_VERSION;
            wrapper.state = context != null ? context.getState() : null;
            String json = objectMapper.writeValueAsString(wrapper);
            String stored = encodeCompressed(json);
            
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(stored, CONTEXT_TTL_HOURS, TimeUnit.HOURS);
            
            log.debug("[ContextPersist] 保存执行上下文: playlistId={}, stage={}", 
                playlistId, context.getCurrentStage());
        } catch (JsonProcessingException e) {
            log.error("[ContextPersist] 序列化上下文失败: playlistId={}", playlistId, e);
        } catch (Exception e) {
            markRedisFailed("saveContext", e);
        }
    }
    
    /**
     * 加载执行上下文（用于断点续跑）
     * 
     * 注意：只恢复 AgentState，WorkingMemory 需要重新生成
     */
    public PlaylistContext loadContext(Long playlistId) {
        if (!isRedisUsable()) {
            return null;
        }
        try {
            String key = getContextKey(playlistId);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String stored = bucket.get();
            
            if (stored == null) {
                log.debug("[ContextPersist] 未找到上下文: playlistId={}", playlistId);
                return null;
            }
            
            String json = decodeCompressed(stored);
            
            // 反序列化 SnapshotWrapper 或旧版 AgentState
            AgentState state;
            int ctxVersion;
            try {
                SnapshotWrapper wrapper = objectMapper.readValue(json, SnapshotWrapper.class);
                if (wrapper != null && wrapper.state != null) {
                    state = wrapper.state;
                    ctxVersion = wrapper.contextVersion != null ? wrapper.contextVersion : PlaylistContext.CONTEXT_VERSION;
                } else {
                    state = objectMapper.readValue(json, AgentState.class);
                    ctxVersion = PlaylistContext.CONTEXT_VERSION;
                }
            } catch (Exception ex) {
                // 兼容旧版本：直接反序列化 AgentState
                state = objectMapper.readValue(json, AgentState.class);
                ctxVersion = PlaylistContext.CONTEXT_VERSION;
            }
            
            // 重建 PlaylistContext
            PlaylistContext context = new PlaylistContext();
            context.setContextVersion(ctxVersion);
            context.setState(state);
            
            log.info("[ContextPersist] 加载执行上下文: playlistId={}, stage={}", 
                playlistId, context.getCurrentStage());
            
            return context;
        } catch (Exception e) {
            markRedisFailed("loadContext", e);
            return null;
        }
    }
    
    /**
     * 删除执行上下文（完成后清理）
     */
    public void deleteContext(Long playlistId) {
        if (!isRedisUsable()) {
            return;
        }
        String key = getContextKey(playlistId);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.delete();
            log.debug("[ContextPersist] 删除执行上下文: playlistId={}", playlistId);
        } catch (Exception e) {
            markRedisFailed("deleteContext", e);
        }
    }
    
    /**
     * 检查是否存在未完成的上下文
     */
    public boolean hasUnfinishedContext(Long playlistId) {
        if (!isRedisUsable()) {
            return false;
        }
        String key = getContextKey(playlistId);
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            return bucket.isExists();
        } catch (Exception e) {
            markRedisFailed("hasUnfinishedContext", e);
            return false;
        }
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
        if (!isRedisUsable()) {
            return;
        }
        try {
            if (playlistId == null || executionId == null) {
                return;
            }
            String key = getSnapshotKey(playlistId, executionId, step);
            SnapshotWrapper wrapper = new SnapshotWrapper();
            wrapper.contextVersion = context != null ? context.getContextVersion() : PlaylistContext.CONTEXT_VERSION;
            wrapper.state = context != null ? context.getState() : null;
            String json = objectMapper.writeValueAsString(wrapper);
            String stored = encodeCompressed(json);
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(stored, SNAPSHOT_TTL_HOURS, TimeUnit.HOURS);
            log.debug("[ContextPersist] 保存节点快照: playlistId={}, executionId={}, step={}, stage={}",
                playlistId, executionId, step, context.getCurrentStage());
        } catch (JsonProcessingException e) {
            log.error("[ContextPersist] 序列化节点快照失败: playlistId={}, executionId={}, step={}",
                playlistId, executionId, step, e);
        } catch (Exception e) {
            markRedisFailed("saveNodeSnapshot", e);
        }
    }
    
    /**
     * 加载节点快照（用于 Debug Replay）
     */
    public PlaylistContext loadNodeSnapshot(Long playlistId, String executionId, int step) {
        if (!isRedisUsable()) {
            return null;
        }
        try {
            if (playlistId == null || executionId == null) {
                return null;
            }
            String key = getSnapshotKey(playlistId, executionId, step);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String stored = bucket.get();
            if (stored == null) {
                log.debug("[ContextPersist] 未找到节点快照: playlistId={}, executionId={}, step={}",
                    playlistId, executionId, step);
                return null;
            }
            String json = decodeCompressed(stored);
            AgentState state;
            int ctxVersion;
            try {
                SnapshotWrapper wrapper = objectMapper.readValue(json, SnapshotWrapper.class);
                if (wrapper != null && wrapper.state != null) {
                    state = wrapper.state;
                    ctxVersion = wrapper.contextVersion != null ? wrapper.contextVersion : PlaylistContext.CONTEXT_VERSION;
                } else {
                    state = objectMapper.readValue(json, AgentState.class);
                    ctxVersion = PlaylistContext.CONTEXT_VERSION;
                }
            } catch (Exception ex) {
                state = objectMapper.readValue(json, AgentState.class);
                ctxVersion = PlaylistContext.CONTEXT_VERSION;
            }
            PlaylistContext context = new PlaylistContext();
            context.setContextVersion(ctxVersion);
            context.setState(state);
            return context;
        } catch (Exception e) {
            markRedisFailed("loadNodeSnapshot", e);
            return null;
        }
    }

    /**
     * 保存完整执行追踪（用于 Debug Replay）
     */
    public void saveExecutionTrace(ExecutionTrace trace) {
        if (!isRedisUsable()) {
            return;
        }
        if (trace == null || trace.getPlaylistId() == null || trace.getExecutionId() == null) {
            return;
        }
        try {
            String key = getExecutionTraceKey(trace.getPlaylistId(), trace.getExecutionId());
            String json = objectMapper.writeValueAsString(trace);
            String stored = encodeCompressed(json);
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(stored, SNAPSHOT_TTL_HOURS, TimeUnit.HOURS);
            
            // 记录该播放列表的最新执行ID，便于历史对比视图查询
            String indexKey = getExecutionIndexKey(trace.getPlaylistId());
            RBucket<String> indexBucket = redissonClient.getBucket(indexKey);
            indexBucket.set(trace.getExecutionId(), SNAPSHOT_TTL_HOURS, TimeUnit.HOURS);
            
            log.debug("[ContextPersist] 保存执行追踪: playlistId={}, executionId={}",
                trace.getPlaylistId(), trace.getExecutionId());
        } catch (JsonProcessingException e) {
            log.error("[ContextPersist] 序列化执行追踪失败: playlistId={}, executionId={}",
                trace.getPlaylistId(), trace.getExecutionId(), e);
        } catch (Exception e) {
            markRedisFailed("saveExecutionTrace", e);
        }
    }

    /**
     * 加载完整执行追踪
     */
    public ExecutionTrace loadExecutionTrace(Long playlistId, String executionId) {
        if (!isRedisUsable()) {
            return null;
        }
        try {
            if (playlistId == null || executionId == null) {
                return null;
            }
            String key = getExecutionTraceKey(playlistId, executionId);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String stored = bucket.get();
            if (stored == null) {
                log.debug("[ContextPersist] 未找到执行追踪: playlistId={}, executionId={}",
                    playlistId, executionId);
                return null;
            }
            String json = decodeCompressed(stored);
            return objectMapper.readValue(json, ExecutionTrace.class);
        } catch (Exception e) {
            markRedisFailed("loadExecutionTrace", e);
            return null;
        }
    }

    private String encodeCompressed(String json) {
        try {
            byte[] input = json.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                gos.write(input);
            }
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "GZ:" + base64;
        } catch (Exception e) {
            log.warn("[ContextPersist] 压缩失败，回退为原始JSON", e);
            return json;
        }
    }

    private String decodeCompressed(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith("GZ:")) {
            return stored;
        }
        try {
            String base64 = stored.substring(3);
            byte[] compressed = Base64.getDecoder().decode(base64);
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            try (GZIPInputStream gis = new GZIPInputStream(bais);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[512];
                int len;
                while ((len = gis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }
                return baos.toString(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("[ContextPersist] 解压失败，回退为原始存储内容", e);
            return stored;
        }
    }

    /**
     * Snapshot 包装，携带上下文版本信息
     */
    private static class SnapshotWrapper {
        public Integer contextVersion;
        public AgentState state;
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
    
    /**
     * 最新执行索引 Key
     */
    private String getExecutionIndexKey(Long playlistId) {
        return "agent:trace:index:" + playlistId;
    }

    /**
     * 查询某个播放列表的最近一次执行ID
     */
    public String getLatestExecutionId(Long playlistId) {
        if (!isRedisUsable()) {
            return null;
        }
        if (playlistId == null) {
            return null;
        }
        try {
            String key = getExecutionIndexKey(playlistId);
            RBucket<String> bucket = redissonClient.getBucket(key);
            return bucket.get();
        } catch (Exception e) {
            markRedisFailed("getLatestExecutionId", e);
            return null;
        }
    }

    private boolean isRedisUsable() {
        return redisEnabled && redisAvailable.get();
    }

    private void markRedisFailed(String op, Exception e) {
        redisAvailable.set(false);
        if (redisFailureLogged.compareAndSet(false, true)) {
            log.warn("[ContextPersist] Redis unavailable, disable persistence. op={}, reason={}", op,
                e != null ? e.getMessage() : "unknown");
        } else {
            log.debug("[ContextPersist] op={} failed: {}", op, e != null ? e.getMessage() : "unknown");
        }
    }
}
