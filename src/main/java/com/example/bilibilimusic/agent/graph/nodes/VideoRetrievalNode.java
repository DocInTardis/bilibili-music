package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.MultiRecallService;
import com.example.bilibilimusic.service.websocket.WsTopicPublisher;
import com.example.bilibilimusic.skill.RetrievalSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class VideoRetrievalNode implements AgentNode {

    private final MultiRecallService recallService;
    private final RetrievalSkill retrievalSkill;
    private final WsTopicPublisher wsTopicPublisher;

    @Override
    public NodeResult execute(PlaylistContext state) {
        log.info("[RetrievalNode] start recall");
        state.setCurrentStage(PlaylistContext.Stage.VIDEO_RETRIEVAL);

        MultiRecallService.RecallResult recall = recallService.recall(state);
        List<VideoInfo> results = recall != null ? recall.videos() : List.of();

        if (results == null || results.isEmpty()) {
            boolean success = retrievalSkill.execute(state);
            if (!success || state.getSearchResults().isEmpty()) {
                log.warn("[RetrievalNode] recall failed or empty results");
                return NodeResult.failure("no_results");
            }
        } else {
            state.setSearchResults(results);
            state.setRecallChannelCounts(recall.channelCounts());
            state.setRecallQueries(recall.recallQueries());
        }

        pushSearchResults(state);
        log.info("[RetrievalNode] recall done, size={}", state.getSearchResults().size());
        return NodeResult.success();
    }

    private void pushSearchResults(PlaylistContext context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("totalCount", context.getSearchResults().size());
        if (context.getRecallChannelCounts() != null && !context.getRecallChannelCounts().isEmpty()) {
            payload.put("recallChannels", context.getRecallChannelCounts());
        }
        if (context.getRecallQueries() != null && !context.getRecallQueries().isEmpty()) {
            payload.put("recallQueries", context.getRecallQueries());
        }

        if (!context.getSearchResults().isEmpty()) {
            var samples = new ArrayList<Map<String, String>>();
            for (int i = 0; i < Math.min(5, context.getSearchResults().size()); i++) {
                var v = context.getSearchResults().get(i);
                Map<String, String> sample = new HashMap<>();
                sample.put("title", v.getTitle());
                sample.put("author", v.getAuthor());
                sample.put("duration", v.getDuration());
                samples.add(sample);
            }
            payload.put("samples", samples);
        }

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("search_results")
            .stage("VIDEO_RETRIEVAL")
            .content(String.format("recall %d videos", context.getSearchResults().size()))
            .payload(payload)
            .build();
        wsTopicPublisher.send("/topic/messages", msg);
    }
}
