package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 进度更新节点（ProgressUpdate）
 *
 * 负责将当前视频的分析结果、数量估算、决策和整体进度以流式形式推送给前端。
 */
@Slf4j
@RequiredArgsConstructor
public class ProgressUpdateNode implements AgentNode {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        if (index >= state.getSearchResults().size()) {
            return NodeResult.success("loop_control");
        }

        VideoInfo video = state.getSearchResults().get(index);
        state.setCurrentStage(PlaylistContext.Stage.STREAM_FEEDBACK);

        Map<String, Object> payload = new HashMap<>();

        // 视频基本信息
        Map<String, Object> v = new HashMap<>();
        v.put("title", video.getTitle());
        v.put("author", video.getAuthor());
        v.put("duration", video.getDuration());
        v.put("url", video.getUrl());
        payload.put("video", v);

        if (state.getLastContentAnalysis() != null) {
            payload.put("contentAnalysis", state.getLastContentAnalysis());
        }
        if (state.getLastQuantityEstimation() != null) {
            payload.put("quantityEstimation", state.getLastQuantityEstimation());
        }
        if (state.getLastDecisionInfo() != null) {
            payload.put("decision", state.getLastDecisionInfo());
        }

        Map<String, Object> progress = new HashMap<>();
        progress.put("accumulatedCount", state.getAccumulatedCount());
        progress.put("targetCount", state.getIntent().getTargetCount());
        progress.put("currentIndex", state.getCurrentVideoIndex() + 1);
        progress.put("total", state.getSearchResults().size());
        payload.put("progress", progress);

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stream_update")
            .stage("VIDEO_JUDGEMENT_LOOP")
            .content("已评估一个视频")
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);

        return NodeResult.success("loop_control");
    }
}
