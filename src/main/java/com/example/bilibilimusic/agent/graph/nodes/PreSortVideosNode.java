package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.config.AgentPrefetchConfig;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 预排序节点：对搜索结果按优先级排序（使用 Redis 偏好缓存）
 *
 * 对应原 PlaylistAgent.runVideoJudgementLoop 中的排序逻辑：
 * - 非合集优先
 * - 精准匹配优先（含偏好加成）
 * - 3-5 分钟时长优先
 * - 播放量高优先
 * - 评论数高优先
 */
@Slf4j
@RequiredArgsConstructor
public class PreSortVideosNode implements AgentNode {
    
    private final UserPreferenceService preferenceService;
    private final CacheService cacheService;
    private final VideoRelevanceScorer relevanceScorer;
    private final AgentPrefetchConfig agentPrefetchConfig;

    @Override
    public NodeResult execute(PlaylistContext state) {
        List<VideoInfo> videos = state.getSearchResults();
        UserIntent intent = state.getIntent();
        if (videos == null || videos.isEmpty()) {
            log.warn("[PreSort] 搜索结果为空，无需排序");
            return NodeResult.success("content_analysis");
        }
        
        log.info("[PreSort] 开始对 {} 个视频进行预排序", videos.size());
        
        // 先做一层基于本地规则的硬过滤（明显不符合需求的视频直接丢入垃圾桶，减少后续处理开销）
        List<VideoInfo> original = new ArrayList<>(videos);
        List<VideoInfo> filtered = new ArrayList<>();
        for (VideoInfo v : original) {
            if (shouldHardReject(v, intent)) {
                state.getTrashVideos().add(v);
            } else {
                filtered.add(v);
            }
        }
        log.info("[PreSort] 本地规则过滤后剩余 {} 个候选（原始 {} 个）", filtered.size(), original.size());
        if (filtered.isEmpty()) {
            state.setSearchResults(filtered);
            state.setCurrentVideoIndex(0);
            state.setAccumulatedCount(0);
            state.setTargetReached(false);
            state.setShouldContinue(false);
            return NodeResult.success("content_analysis");
        }
        
        // 从数据库获取用户偏好权重（个性化推荐，含时间衰减）
        Long conversationId = state.getConversationId();
        Long userId = state.getUserId();
        Map<String, Integer> artistPrefs = userId != null 
            ? preferenceService.getUserArtistPreferences(userId)
            : preferenceService.getArtistPreferences(conversationId);
        Map<String, Integer> keywordPrefs = userId != null 
            ? preferenceService.getUserKeywordPreferences(userId)
            : preferenceService.getKeywordPreferences(conversationId);
                                
        log.info("[PreSort] 加载偏好权重 - userId={}, 艺人: {}, 关键词: {}", userId, artistPrefs.size(), keywordPrefs.size());
                
        // 使用并行流计算排序权重并排序，充分利用多核能力
        Comparator<VideoInfo> comparator = Comparator.comparing((VideoInfo v) -> isPlaylistStyle(v))
            .thenComparing((VideoInfo v) -> -calculateKeywordMatchScoreWithPreference(v, intent, artistPrefs, keywordPrefs))
            .thenComparingInt((VideoInfo v) -> calculateDeviationFromOptimal(
                parseDurationToSeconds(v.getDuration()), 180, 300))
            .thenComparing((VideoInfo v) -> v.getPlayCount() != null ? -v.getPlayCount() : 0L)
            .thenComparing((VideoInfo v) -> v.getCommentCount() != null ? -v.getCommentCount() : 0L);
        
        List<VideoInfo> sorted = filtered.parallelStream()
            .sorted(comparator)
            .collect(Collectors.toList());
        
        state.setSearchResults(sorted);
        
        // 初始化循环控制字段
        state.setCurrentVideoIndex(0);
        state.setAccumulatedCount(0);
        state.setTargetReached(false);
        state.setShouldContinue(true);

        // 并行预热：提前把“相关性评分结果”写入缓存，降低后续逐个判断的总耗时
        prefetchScoringCache(state, artistPrefs, keywordPrefs, sorted);
        
        return NodeResult.success("content_analysis");
    }

    private void prefetchScoringCache(PlaylistContext state,
                                      Map<String, Integer> artistPrefs,
                                      Map<String, Integer> keywordPrefs,
                                      List<VideoInfo> sortedVideos) {
        boolean enabled = agentPrefetchConfig != null && agentPrefetchConfig.isScoringEnabled();
        int maxVideos = agentPrefetchConfig != null ? agentPrefetchConfig.getScoringMaxVideos() : 0;
        if (!enabled || sortedVideos == null || sortedVideos.isEmpty() || maxVideos <= 0) {
            return;
        }

        int limit = Math.min(maxVideos, sortedVideos.size());
        List<VideoInfo> slice = sortedVideos.subList(0, limit);
        long start = System.currentTimeMillis();
        try {
            slice.parallelStream().forEach(v -> {
                if (v == null || v.getBvid() == null || v.getBvid().isBlank()) {
                    return;
                }
                VideoRelevanceScorer.ScoringResult scoringResult =
                    relevanceScorer.scoreVideo(v, state.getIntent(), artistPrefs, keywordPrefs, state.getConversationId());
                cacheService.cacheLLMJudgement(v.getBvid(), state.getIntent(), scoringResult);
            });
        } catch (Exception e) {
            log.debug("[PreSort] 评分预热失败（忽略，不影响主流程）: {}", e.getMessage());
        } finally {
            long cost = System.currentTimeMillis() - start;
            log.info("[PreSort] 评分缓存预热完成: videos={}, cost={}ms", limit, cost);
        }
    }

    private boolean isPlaylistStyle(VideoInfo video) {
        String title = video.getTitle();
        if (title == null) return false;
        String t = title.toLowerCase();
        return t.contains("合集") || t.contains("歌单") || t.contains("串烧")
            || t.contains("mix") || t.contains("playlist") || t.contains("连播");
    }

    private boolean shouldHardReject(VideoInfo video, UserIntent intent) {
        StringBuilder sb = new StringBuilder();
        if (video.getTitle() != null) sb.append(video.getTitle()).append(' ');
        if (video.getTags() != null) sb.append(video.getTags()).append(' ');
        if (video.getDescription() != null) sb.append(video.getDescription()).append(' ');
        String text = sb.toString().toLowerCase();

        // 负向关键词粗过滤
        String[] negativeKeywords = {
            "教程", "教学", "教程", "reaction", "剪辑", "混剪", "集锦", "解说", "讲解", "翻唱", "现场", "live"
        };
        for (String nk : negativeKeywords) {
            if (nk == null || nk.isBlank()) continue;
            if (text.contains(nk.toLowerCase())) {
                log.debug("[PreSort] 负向关键词过滤: {} -> {}", nk, video.getTitle());
                return true;
            }
        }

        // 明显异常时长过滤（过短或过长的视频）
        int durationSeconds = parseDurationToSeconds(video.getDuration());
        if (durationSeconds > 0 && (durationSeconds < 30 || durationSeconds > 3600)) {
            log.debug("[PreSort] 时长异常过滤: {}s -> {}", durationSeconds, video.getTitle());
            return true;
        }

        return false;
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

    /**
     * 计算关键词匹配分数（含偏好加成）
     */
    private int calculateKeywordMatchScoreWithPreference(VideoInfo video, UserIntent intent, 
                                                         Map<String, Integer> artistPrefs, 
                                                         Map<String, Integer> keywordPrefs) {
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
        
        // 基础关键词匹配分数
        for (String k : kws) {
            if (k == null || k.isBlank()) continue;
            if (haystack.contains(k.toLowerCase())) {
                score++;
                
                // 偏好加成：如果关键词在偏好中，额外加分
                Integer prefWeight = keywordPrefs.get(k.toLowerCase());
                if (prefWeight != null) {
                    score += prefWeight;  // 偏好权重直接加到分数上
                    log.debug("[PreSort] 关键词偏好加成: {} (+{})", k, prefWeight);
                }
            }
        }
        
        // 艺人偏好加成
        if (video.getAuthor() != null) {
            String author = video.getAuthor().toLowerCase();
            for (Map.Entry<String, Integer> entry : artistPrefs.entrySet()) {
                if (author.contains(entry.getKey().toLowerCase())) {
                    score += entry.getValue();
                    log.debug("[PreSort] 艺人偏好加成: {} (+{})", entry.getKey(), entry.getValue());
                    break; // 只匹配一次
                }
            }
        }
        
        return score;
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
