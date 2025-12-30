package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.MusicUnit;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相关性决策节点（RelevanceDecision）
 */
@Slf4j
@RequiredArgsConstructor
public class RelevanceDecisionNode implements AgentNode {

    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        if (index >= state.getSearchResults().size()) {
            state.setShouldContinue(false);
            return NodeResult.success("progress_update");
        }

        VideoInfo video = state.getSearchResults().get(index);
        state.setCurrentStage(PlaylistContext.Stage.CANDIDATE_DECISION);

        UserIntent intent = state.getIntent();
        boolean accepted = isRelevantToIntent(video, intent);
        String decisionReason = accepted ? "标题/标签与需求较为匹配" : "与需求相关度较低";

        Map<String, Object> decisionInfo = new HashMap<>();
        decisionInfo.put("accepted", accepted);
        decisionInfo.put("reason", decisionReason);
        state.setLastDecisionInfo(decisionInfo);

        if (accepted) {
            // 从数量估算中读取估算结果
            Map<String, Object> quantity = state.getLastQuantityEstimation();
            int estimatedCount = 1;
            boolean isPlaylist = false;
            if (quantity != null) {
                Object ec = quantity.get("estimatedCount");
                if (ec instanceof Number) {
                    estimatedCount = ((Number) ec).intValue();
                }
                Object ip = quantity.get("isPlaylist");
                if (ip instanceof Boolean) {
                    isPlaylist = (Boolean) ip;
                }
            }

            MusicUnit unit = MusicUnit.builder()
                .title(video.getTitle())
                .artist(video.getAuthor())
                .sourceVideo(video)
                .estimatedCount(estimatedCount)
                .reason(decisionReason)
                .playlistStyle(isPlaylist)
                .build();
            state.getMusicUnits().add(unit);
            state.getSelectedVideos().add(video);
            state.setAccumulatedCount(state.getAccumulatedCount() + estimatedCount);

            // 记录当前视频被接受，以便 VideoAcceptedNode 可以读取
            Map<String, Object> acceptedMeta = new HashMap<>();
            acceptedMeta.put("estimatedCount", estimatedCount);
            state.setLastDecisionInfo(decisionInfo);
        } else {
            state.getTrashVideos().add(video);
        }

        return NodeResult.success(accepted ? "video_accepted" : "progress_update");
    }

    private boolean isRelevantToIntent(VideoInfo video, UserIntent intent) {
        StringBuilder sb = new StringBuilder();
        if (video.getTitle() != null) sb.append(video.getTitle()).append(' ');
        if (video.getTags() != null) sb.append(video.getTags()).append(' ');
        if (video.getDescription() != null) sb.append(video.getDescription()).append(' ');
        if (video.getAuthor() != null) sb.append(video.getAuthor());
        String haystack = sb.toString().toLowerCase();

        List<String> kws = intent.getKeywords();
        if (kws == null || kws.isEmpty()) {
            if (intent.getQuery() != null && !intent.getQuery().isBlank()) {
                kws = List.of(intent.getQuery());
            } else {
                return true;
            }
        }

        for (String k : kws) {
            if (k == null || k.isBlank()) continue;
            if (haystack.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
