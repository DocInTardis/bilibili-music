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
 * 内容可理解性分析节点（ContentAnalysis）
 */
@Slf4j
@RequiredArgsConstructor
public class ContentAnalysisNode implements AgentNode {

    private final SimpMessagingTemplate messagingTemplate;

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

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("hasTitle", hasTitle);
        analysis.put("hasTags", hasTags);
        analysis.put("hasDescription", hasDescription);
        analysis.put("understandable", understandable);
        state.setLastContentAnalysis(analysis);
        state.setCurrentUnderstandable(understandable);

        if (!understandable) {
            log.debug("[ContentAnalysis] 视频缺少标题/标签/简介，暂存为候选: {}", video.getTitle());
            state.getTrashVideos().add(video);

            // 发送一次 CONTENT_ANALYSIS 阶段的流式反馈（与原 runVideoJudgementLoop 保持一致）
            Map<String, Object> payload = new HashMap<>();
            Map<String, Object> v = new HashMap<>();
            v.put("title", video.getTitle());
            v.put("author", video.getAuthor());
            v.put("duration", video.getDuration());
            v.put("url", video.getUrl());
            payload.put("video", v);
            payload.put("contentAnalysis", analysis);

            com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
                .type("stream_update")
                .stage("CONTENT_ANALYSIS")
                .content("视频缺少标题/标签/简介，暂存为候选")
                .payload(payload)
                .build();
            messagingTemplate.convertAndSend("/topic/messages", msg);
        }

        return NodeResult.success("quantity_estimation");
    }
}
