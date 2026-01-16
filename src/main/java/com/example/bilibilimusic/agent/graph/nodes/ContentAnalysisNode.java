package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.telemetry.DecisionTelemetryService;
import com.example.bilibilimusic.service.websocket.WsTopicPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * 内容可理解性分析节点（ContentAnalysis）
 */
@Slf4j
@RequiredArgsConstructor
public class ContentAnalysisNode implements AgentNode {

    private final WsTopicPublisher wsTopicPublisher;
    private final DecisionTelemetryService decisionTelemetryService;

    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        if (index >= state.getSearchResults().size()) {
            log.info("[ContentAnalysis] 已无更多视频可处理");
            state.setShouldContinue(false);
            return NodeResult.success("progress_update");
        }

        VideoInfo video = state.getSearchResults().get(index);
        state.setCurrentStage(PlaylistContext.Stage.CONTENT_ANALYSIS);

        boolean hasTitle = video.getTitle() != null && !video.getTitle().isBlank();
        boolean hasTags = video.getTags() != null && !video.getTags().isBlank();
        boolean hasDescription = video.getDescription() != null && !video.getDescription().isBlank();
        boolean understandable = hasTitle || hasTags || hasDescription;

        List<String> missingFields = new ArrayList<>();
        if (!hasTitle) missingFields.add("title");
        if (!hasTags) missingFields.add("tags");
        if (!hasDescription) missingFields.add("description");

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("hasTitle", hasTitle);
        analysis.put("hasTags", hasTags);
        analysis.put("hasDescription", hasDescription);
        analysis.put("understandable", understandable);
        analysis.put("missingFields", missingFields);
        state.setLastContentAnalysis(analysis);
        state.setCurrentUnderstandable(understandable);

        if (!understandable) {
            log.debug("[ContentAnalysis] 视频缺少标题/标签/简介，暂存为候选: {}", video.getTitle());
            state.getTrashVideos().add(video);

            // 避免后续 ProgressUpdateNode 复用上一条视频的 decision/quantity 信息
            state.setLastQuantityEstimation(null);
            Map<String, Object> decisionInfo = new HashMap<>();
            decisionInfo.put("accepted", false);
            decisionInfo.put("score", null);
            decisionInfo.put("reason", "缺少标题/标签/简介，无法判断相关性");
            decisionInfo.put("reasonCategory", "MISSING_METADATA");
            decisionInfo.put("missingFields", missingFields);
            state.setLastDecisionInfo(decisionInfo);

            decisionTelemetryService.recordDecision(
                state.getConversationId(),
                "video",
                video.getBvid(),
                DecisionTelemetryService.Source.RULE,
                false,
                null,
                "MISSING_METADATA",
                null
            );

            // 发送一次 CONTENT_ANALYSIS 阶段的流式反馈（与原 runVideoJudgementLoop 保持一致）
            Map<String, Object> payload = new HashMap<>();
            Map<String, Object> v = new HashMap<>();
            v.put("title", video.getTitle());
            v.put("author", video.getAuthor());
            v.put("duration", video.getDuration());
            v.put("url", video.getUrl());
            payload.put("video", v);
            payload.put("contentAnalysis", analysis);
            payload.put("trash", decisionInfo);

            com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
                .type("stream_update")
                .stage("CONTENT_ANALYSIS")
                .content("视频信息缺失：已移入垃圾桶（可解释原因）")
                .payload(payload)
                .build();
            wsTopicPublisher.send("/topic/messages", msg);
        }

        return NodeResult.success();
    }
}
