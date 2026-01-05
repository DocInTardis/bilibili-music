package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.ErrorStats;
import com.example.bilibilimusic.dto.ExecutionOverview;
import com.example.bilibilimusic.entity.AgentBehaviorLog;
import com.example.bilibilimusic.mapper.AgentBehaviorLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Agent 可观测性聚合服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ObservabilityService {

    private final AgentBehaviorLogMapper behaviorLogMapper;
    private final ContextPersistenceService contextPersistenceService;

    /**
     * 最近 N 次执行的概要信息
     */
    public List<ExecutionOverview> getRecentExecutions(int limit, int scanLimit) {
        if (limit <= 0) {
            limit = 10;
        }
        if (scanLimit < limit * 10) {
            scanLimit = limit * 10;
        }
        List<AgentBehaviorLog> logs = behaviorLogMapper.selectLatestLogs(scanLimit);
        Map<Long, ExecutionAggregate> aggregateMap = new LinkedHashMap<>();

        for (AgentBehaviorLog logEntity : logs) {
            Long playlistId = logEntity.getPlaylistId();
            if (playlistId == null) {
                continue;
            }
            ExecutionAggregate agg = aggregateMap.computeIfAbsent(playlistId, id -> new ExecutionAggregate());
            agg.playlistId = playlistId;
            agg.conversationId = logEntity.getConversationId();
            if (agg.lastTime == null || logEntity.getCreatedAt().isAfter(agg.lastTime)) {
                agg.lastTime = logEntity.getCreatedAt();
            }
            String type = logEntity.getBehaviorType();
            if ("NODE_EXIT".equals(type)) {
                agg.nodeExitCount++;
                if (logEntity.getDurationMs() != null) {
                    agg.totalDurationMs += logEntity.getDurationMs();
                }
                if (Boolean.FALSE.equals(logEntity.getSuccess())) {
                    agg.failedNodeCount++;
                    if (logEntity.getNodeName() != null) {
                        agg.failedNodes.add(logEntity.getNodeName());
                    }
                }
            }
            if ("ERROR".equals(type)) {
                agg.hasError = true;
                if (logEntity.getNodeName() != null) {
                    agg.failedNodes.add(logEntity.getNodeName());
                }
            }
            if (aggregateMap.size() >= limit) {
                // 已经收集到足够多的不同执行
                break;
            }
        }

        List<ExecutionOverview> result = new ArrayList<>();
        for (ExecutionAggregate agg : aggregateMap.values()) {
            long avgDuration = agg.nodeExitCount > 0 ? agg.totalDurationMs / agg.nodeExitCount : 0L;
            String latestExecutionId = contextPersistenceService.getLatestExecutionId(agg.playlistId);
            result.add(ExecutionOverview.builder()
                .playlistId(agg.playlistId)
                .conversationId(agg.conversationId)
                .lastTime(agg.lastTime)
                .nodeExitCount(agg.nodeExitCount)
                .failedNodeCount(agg.failedNodeCount)
                .avgNodeDurationMs(avgDuration)
                .hasError(agg.hasError)
                .failedNodes(new ArrayList<>(agg.failedNodes))
                .latestExecutionId(latestExecutionId)
                .build());
        }
        return result;
    }

    /**
     * 统计窗口内的常见错误
     */
    public ErrorStats getErrorStats(int hours) {
        if (hours <= 0) {
            hours = 24;
        }
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<AgentBehaviorLog> logs = behaviorLogMapper.selectErrorsSince(since);

        long total = 0L;
        long emptySearch = 0L;
        long llmTimeout = 0L;
        long other = 0L;

        for (AgentBehaviorLog logEntity : logs) {
            total++;
            String msg = Optional.ofNullable(logEntity.getErrorMessage()).orElse("");
            String desc = Optional.ofNullable(logEntity.getDescription()).orElse("");
            String combined = (msg + " " + desc).toLowerCase();

            if (combined.contains("搜索结果为空") || combined.contains("no search result") || combined.contains("empty search")) {
                emptySearch++;
            } else if (combined.contains("llm 调用失败") || combined.contains("llm 调用超时")
                    || combined.contains("llm timeout") || combined.contains("llm failed")) {
                llmTimeout++;
            } else {
                other++;
            }
        }

        return ErrorStats.builder()
            .totalErrors(total)
            .emptySearchErrors(emptySearch)
            .llmTimeoutErrors(llmTimeout)
            .otherErrors(other)
            .build();
    }

    private static class ExecutionAggregate {
        Long playlistId;
        Long conversationId;
        LocalDateTime lastTime;
        int nodeExitCount;
        int failedNodeCount;
        long totalDurationMs;
        boolean hasError;
        Set<String> failedNodes = new LinkedHashSet<>();
    }
}
