package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.agent.PlaylistAgent;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.dto.SavePlaylistRequest;
import com.example.bilibilimusic.dto.ExecutionTrace;
import com.example.bilibilimusic.dto.NodeTrace;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.service.DatabaseService;
import com.example.bilibilimusic.service.ContextPersistenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API 接口
 * 语义更清晰，但不破坏已有调用方
 */
@RestController
@RequestMapping("/api/playlist")
@RequiredArgsConstructor
@Slf4j
public class PlaylistController {

    private final PlaylistAgent playlistAgent;
    private final DatabaseService databaseService;
    private final ContextPersistenceService contextPersistenceService;

    @PostMapping
    public ResponseEntity<PlaylistResponse> generate(@Valid @RequestBody PlaylistRequest request) {
        log.info("[REST API] 收到歌单生成请求：{}", request.getQuery());
        
        // 使用 Agent 执行任务（REST 接口不需要状态推送）
        PlaylistResponse response = playlistAgent.execute(request, status -> {
            log.debug("[REST API] 状态：{}", status);
        });
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Debug 单步执行：从某个快照开始，自动前进一步（跑到下一节点就停）
     */
    @PostMapping("/debug/step")
    public ResponseEntity<PlaylistResponse> debugStep(@RequestParam Long playlistId,
                                                      @RequestParam String executionId,
                                                      @RequestParam int fromStep) {
        log.info("[REST API] Debug 单步执行请求: playlistId={}, executionId={}, fromStep={}",
            playlistId, executionId, fromStep);

        ExecutionTrace trace = contextPersistenceService.loadExecutionTrace(playlistId, executionId);
        if (trace == null || trace.getNodeTraces() == null || trace.getNodeTraces().isEmpty()) {
            log.warn("[REST API] 未找到执行轨迹，无法单步执行: playlistId={}, executionId={}", playlistId, executionId);
            return ResponseEntity.badRequest().build();
        }

        int nextIndex = fromStep;
        String stopNode = null;
        if (nextIndex >= 0 && nextIndex < trace.getNodeTraces().size()) {
            NodeTrace nextNode = trace.getNodeTraces().get(nextIndex);
            stopNode = nextNode.getNodeName();
        }

        PlaylistResponse response = playlistAgent.debugReplay(playlistId, executionId, fromStep, stopNode, status -> {
            log.debug("[REST API][DebugStep] 状态: {}", status);
        });
        return ResponseEntity.ok(response);
    }

    /**
     * Debug 重跑：从快照恢复并重新执行状态机
     */
    @PostMapping("/debug/replay")
    public ResponseEntity<PlaylistResponse> debugReplay(@RequestParam Long playlistId,
                                                        @RequestParam String executionId,
                                                        @RequestParam int step,
                                                        @RequestParam(required = false) String stopNode) {
        log.info("[REST API] Debug 重跑请求: playlistId={}, executionId={}, step={}, stopNode={}",
            playlistId, executionId, step, stopNode);
        PlaylistResponse response = playlistAgent.debugReplay(playlistId, executionId, step, stopNode, status -> {
            log.debug("[REST API][DebugReplay] 状态: {}", status);
        });
        return ResponseEntity.ok(response);
    }

    /**
     * 查询某次执行的完整 ExecutionTrace（用于 Debug Replay）
     */
    @GetMapping("/debug/trace")
    public ResponseEntity<ExecutionTrace> getExecutionTrace(@RequestParam Long playlistId,
                                                            @RequestParam String executionId) {
        ExecutionTrace trace = contextPersistenceService.loadExecutionTrace(playlistId, executionId);
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trace);
    }

    /**
     * 查询某个节点快照，对应 ExecutionTrace 中的 step（0-based）
     */
    @GetMapping("/debug/snapshot")
    public ResponseEntity<PlaylistContext> getSnapshot(@RequestParam Long playlistId,
                                                       @RequestParam String executionId,
                                                       @RequestParam int step) {
        PlaylistContext context = contextPersistenceService.loadNodeSnapshot(playlistId, executionId, step);
        if (context == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(context);
    }

    /**
     * 增加视频权重（点击爱心按钮）
     */
    @PostMapping("/item/{itemId}/like")
    public ResponseEntity<Void> likeItem(@PathVariable Long itemId) {
        log.info("[REST API] 增加视频权重: itemId={}", itemId);
        databaseService.increaseItemWeight(itemId);
        return ResponseEntity.ok().build();
    }
    
    /**
     * 保存播放列表并重命名
     */
    @PostMapping("/save")
    public ResponseEntity<Void> savePlaylist(@RequestBody SavePlaylistRequest request) {
        log.info("[REST API] 保存播放列表: playlistId={}, name={}", request.getPlaylistId(), request.getName());
        databaseService.savePlaylistWithName(request.getPlaylistId(), request.getName());
        return ResponseEntity.ok().build();
    }
}
