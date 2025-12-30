package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;

/**
 * 预排序节点：对搜索结果按优先级排序
 *
 * 对应原 PlaylistAgent.runVideoJudgementLoop 中的排序逻辑：
 * - 非合集优先
 * - 精准匹配优先
 * - 3-5 分钟时长优先
 * - 播放量高优先
 * - 评论数高优先
 */
@Slf4j
@RequiredArgsConstructor
public class PreSortVideosNode implements AgentNode {

    @Override
    public NodeResult execute(PlaylistContext state) {
        List<VideoInfo> videos = state.getSearchResults();
        UserIntent intent = state.getIntent();
        if (videos == null || videos.isEmpty()) {
            log.warn("[PreSort] 搜索结果为空，无需排序");
            return NodeResult.success("content_analysis");
        }

        log.info("[PreSort] 开始对 {} 个视频进行预排序", videos.size());

        videos.sort(Comparator.comparing((VideoInfo v) -> isPlaylistStyle(v))
            .thenComparing((VideoInfo v) -> -calculateKeywordMatchScore(v, intent))
            .thenComparingInt((VideoInfo v) -> calculateDeviationFromOptimal(
                parseDurationToSeconds(v.getDuration()), 180, 300))
            .thenComparing((VideoInfo v) -> v.getPlayCount() != null ? -v.getPlayCount() : 0L)
            .thenComparing((VideoInfo v) -> v.getCommentCount() != null ? -v.getCommentCount() : 0L)
        );

        // 初始化循环控制字段
        state.setCurrentVideoIndex(0);
        state.setAccumulatedCount(0);
        state.setTargetReached(false);
        state.setShouldContinue(true);

        return NodeResult.success("content_analysis");
    }

    private boolean isPlaylistStyle(VideoInfo video) {
        String title = video.getTitle();
        if (title == null) return false;
        String t = title.toLowerCase();
        return t.contains("合集") || t.contains("歌单") || t.contains("串烧")
            || t.contains("mix") || t.contains("playlist") || t.contains("连播");
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

    private int calculateDeviationFromOptimal(int durationSeconds, int optimalMin, int optimalMax) {
        if (durationSeconds <= 0) {
            return Integer.MAX_VALUE;
        }
        if (durationSeconds >= optimalMin && durationSeconds <= optimalMax) {
            return 0;
        }
        if (durationSeconds < optimalMin) {
            return optimalMin - durationSeconds;
        }
        return durationSeconds - optimalMax;
    }

    private int calculateKeywordMatchScore(VideoInfo video, UserIntent intent) {
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
                return 0;
            }
        }

        int score = 0;
        for (String k : kws) {
            if (k == null || k.isBlank()) continue;
            if (haystack.contains(k.toLowerCase())) {
                score++;
            }
        }
        return score;
    }
}
