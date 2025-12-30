package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 数量估算节点（QuantityEstimation）
 */
@Slf4j
@RequiredArgsConstructor
public class QuantityEstimationNode implements AgentNode {

    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        if (index >= state.getSearchResults().size()) {
            state.setShouldContinue(false);
            return NodeResult.success("progress_update");
        }

        VideoInfo video = state.getSearchResults().get(index);
        state.setCurrentStage(PlaylistContext.Stage.QUANTITY_ESTIMATION);

        boolean isPlaylist = isPlaylistStyle(video);
        int estimatedCount = isPlaylist ? 1 : estimateSongCount(video);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("estimatedCount", estimatedCount);
        quantity.put("isPlaylist", isPlaylist);
        quantity.put("method", isPlaylist ? "playlist_treated_as_single" : "approx_by_duration_or_title");

        state.setLastQuantityEstimation(quantity);

        return NodeResult.success("relevance_decision");
    }

    private int estimateSongCount(VideoInfo video) {
        int seconds = parseDurationToSeconds(video.getDuration());
        if (seconds <= 0) {
            return 1;
        }
        double minutes = seconds / 60.0;
        int approx = (int) Math.max(1, Math.round(minutes / 4.0));
        if (isPlaylistStyle(video) && approx < 3) {
            approx = 3;
        }
        return approx;
    }

    private int parseDurationToSeconds(String duration) {
        if (duration == null || duration.isBlank()) {
            return 0;
        }
        String[] parts = duration.trim().split(":");
        try {
            if (parts.length == 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int s = Integer.parseInt(parts[2]);
                return h * 3600 + m * 60 + s;
            } else if (parts.length == 2) {
                int m = Integer.parseInt(parts[0]);
                int s = Integer.parseInt(parts[1]);
                return m * 60 + s;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private boolean isPlaylistStyle(VideoInfo video) {
        String title = video.getTitle();
        if (title == null) return false;
        String t = title.toLowerCase();
        return t.contains("合集") || t.contains("歌单") || t.contains("串烧")
            || t.contains("mix") || t.contains("playlist") || t.contains("连播");
    }
}
