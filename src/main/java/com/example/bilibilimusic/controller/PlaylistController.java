package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.agent.PlaylistAgent;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.dto.SavePlaylistRequest;
import com.example.bilibilimusic.dto.ExecutionTrace;
import com.example.bilibilimusic.dto.NodeTrace;
import com.example.bilibilimusic.dto.ExecutionOverview;
import com.example.bilibilimusic.dto.ErrorStats;
import com.example.bilibilimusic.dto.UserBehaviorRequest;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.UserBehaviorEvent;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.service.DatabaseService;
import com.example.bilibilimusic.service.ContextPersistenceService;
import com.example.bilibilimusic.service.ObservabilityService;
import com.example.bilibilimusic.service.PromptVersionService;
import com.example.bilibilimusic.service.UserBehaviorFeedbackService;
import com.example.bilibilimusic.service.telemetry.DecisionTelemetryService;
import com.example.bilibilimusic.service.telemetry.LlmTelemetryService;
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
    private final ObservabilityService observabilityService;
    private final UserBehaviorFeedbackService userBehaviorFeedbackService;
    private final PromptVersionService promptVersionService;
    private final LlmTelemetryService llmTelemetryService;
    private final DecisionTelemetryService decisionTelemetryService;

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
     * 最近 N 次执行的概要信息
     */
    @GetMapping("/observability/recent-executions")
    public ResponseEntity<java.util.List<ExecutionOverview>> getRecentExecutions(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(observabilityService.getRecentExecutions(limit, limit * 10));
    }

    /**
     * 常见错误聚合统计
     */
    @GetMapping("/observability/error-stats")
    public ResponseEntity<ErrorStats> getErrorStats(@RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(observabilityService.getErrorStats(hours));
    }

    /**
     * 当前 Prompt 版本信息（用于可观测与 A/B 对比）
     */
    @GetMapping("/observability/prompt-versions")
    public ResponseEntity<java.util.Map<String, String>> getPromptVersions() {
        return ResponseEntity.ok(promptVersionService.getAllVersions());
    }

    /**
     * LLM 调用统计（按 nodeName + version 聚合）
     */
    @GetMapping("/observability/llm-stats")
    public ResponseEntity<LlmTelemetryService.Snapshot> getLlmStats() {
        return ResponseEntity.ok(llmTelemetryService.snapshot());
    }

    /**
     * 决策统计（接受/拒绝、以及用户反馈回流后的准确率）
     */
    @GetMapping("/observability/decision-stats")
    public ResponseEntity<DecisionTelemetryService.Snapshot> getDecisionStats() {
        return ResponseEntity.ok(decisionTelemetryService.snapshot());
    }

    /**
     * 导出 Agent 状态图结构，便于前端做可视化展示。
     */
    @GetMapping("/graph/visualize")
    public ResponseEntity<String> visualizeGraph(@RequestParam(value = "mode", required = false) String mode) {
        String graphText = playlistAgent.visualizeGraph(mode);
        return ResponseEntity.ok(graphText);
    }

    /**
     * 手动输入视频 URL，将视频加入指定播放列表
     */
    @PostMapping("/item/add-by-url")
    public ResponseEntity<Void> addItemByUrl(@RequestParam Long playlistId,
                                             @RequestParam String url,
                                             @RequestParam(required = false, defaultValue = "手动添加") String reason) {
        log.info("[REST API] 手动添加视频: playlistId={}, url={}", playlistId, url);
        databaseService.addVideoToPlaylistByUrl(playlistId, url, reason);
        return ResponseEntity.ok().build();
    }

    /**
     * 每日推荐榜单：随机返回若干视频
     */
    @GetMapping("/daily/recommend")
    public ResponseEntity<PlaylistResponse> getDailyRecommend(@RequestParam(defaultValue = "10") int limit) {
        java.util.List<VideoInfo> videos = databaseService.getRandomRecommendations(limit);
        PlaylistResponse response = PlaylistResponse.builder()
            .videos(videos)
            .summary(String.format("为您随机推荐 %d 首 B 站音乐视频", videos != null ? videos.size() : 0))
            .build();
        return ResponseEntity.ok(response);
    }

    /**
     * 前端上报用户行为（喜欢/不喜欢/跳过/播放时长/删除等）
     */
    @PostMapping("/behavior")
    public ResponseEntity<Void> reportBehavior(@RequestBody UserBehaviorRequest request) {
        try {
            UserBehaviorEvent.BehaviorType type = UserBehaviorEvent.BehaviorType.valueOf(request.getBehaviorType());
            UserBehaviorEvent event = UserBehaviorEvent.builder()
                .conversationId(request.getConversationId())
                .behaviorType(type)
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .intensity(request.getIntensity())
                .contextJson(request.getContextJson())
                .occurredAt(java.time.LocalDateTime.now())
                .applied(false)
                .build();
            userBehaviorFeedbackService.recordBehavior(event);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST API] 无效的行为类型: {}", request.getBehaviorType());
            return ResponseEntity.badRequest().build();
        }
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
