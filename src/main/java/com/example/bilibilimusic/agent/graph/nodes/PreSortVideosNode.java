package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.config.AgentPrefetchConfig;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.service.RerankService;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern TRACK_NO_PREFIX = Pattern.compile("^\\s*(?:\\[|\\(|#)?\\s*(\\d{1,2})\\s*(?:[\\]\\)\\-_.:]|\\s+)");
    private static final Pattern TRACK_NO_SLASH = Pattern.compile("\\b(\\d{1,2})\\s*/\\s*\\d{1,2}\\b");
    private static final Pattern TRACK_NO_EN = Pattern.compile("(?i)\\btrack\\s*(\\d{1,2})\\b");
    private static final Pattern TRACK_NO_CH = Pattern.compile("\\u7b2c\\s*(\\d{1,2})\\s*\\u9996");
    
    private final UserPreferenceService preferenceService;
    private final CacheService cacheService;
    private final VideoRelevanceScorer relevanceScorer;
    private final AgentPrefetchConfig agentPrefetchConfig;
    private final RerankService rerankService;

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
        
        List<VideoInfo> initialSorted = filtered.parallelStream()
            .sorted(comparator)
            .collect(Collectors.toList());

        List<VideoInfo> sorted = prefetchAndRerank(state, artistPrefs, keywordPrefs, initialSorted);
        if (shouldApplyAlbumOrder(state.getIntent())) {
            for (VideoInfo v : sorted) {
                annotateAlbumTrack(v, state.getIntent());
            }
            sorted = orderByTrackNoPreferPopularity(sorted);
        }
        state.setSearchResults(sorted);
        
        // 初始化循环控制字段
        state.setCurrentVideoIndex(0);
        state.setAccumulatedCount(0);
        state.setTargetReached(false);
        state.setShouldContinue(true);

        // 并行预热：提前把“相关性评分结果”写入缓存，降低后续逐个判断的总耗时
        return NodeResult.success("content_analysis");
    }

    private List<VideoInfo> prefetchAndRerank(PlaylistContext state,
                                      Map<String, Integer> artistPrefs,
                                      Map<String, Integer> keywordPrefs,
                                      List<VideoInfo> sortedVideos) {
        boolean enabled = agentPrefetchConfig != null && agentPrefetchConfig.isScoringEnabled();
        int maxVideos = agentPrefetchConfig != null ? agentPrefetchConfig.getScoringMaxVideos() : 0;
        if (!enabled || sortedVideos == null || sortedVideos.isEmpty() || maxVideos <= 0) {
            return sortedVideos;
        }

        int limit = Math.min(maxVideos, sortedVideos.size());
        List<VideoInfo> slice = sortedVideos.subList(0, limit);
        long start = System.currentTimeMillis();
        Map<String, VideoRelevanceScorer.ScoringResult> results = new ConcurrentHashMap<>();
        Map<String, RerankService.RerankResult> rerankResults = new ConcurrentHashMap<>();
        try {
            slice.parallelStream().forEach(v -> {
                if (v == null || v.getBvid() == null || v.getBvid().isBlank()) {
                    return;
                }
                VideoRelevanceScorer.ScoringResult scoringResult =
                    relevanceScorer.scoreVideo(v, state.getIntent(), artistPrefs, keywordPrefs, state.getConversationId(), state.getUserId());
                results.put(v.getBvid(), scoringResult);
                cacheService.cacheLLMJudgement(v.getBvid(), state.getIntent(), scoringResult);
                if (rerankService != null) {
                    RerankService.RerankResult rerank = rerankService.rerank(v, scoringResult);
                    rerankResults.put(v.getBvid(), rerank);
                    v.setRerankScore(rerank.score());
                    v.setRerankBreakdown(rerank.breakdown());
                    v.setRerankReason(rerank.reason());
                }
            });
        } catch (Exception e) {
            log.debug("[PreSort] 评分预热失败（忽略，不影响主流程）: {}", e.getMessage());
        } finally {
            long cost = System.currentTimeMillis() - start;
            log.info("[PreSort] 评分缓存预热完成: videos={}, cost={}ms", limit, cost);
        }
        if (results.isEmpty()) {
            return sortedVideos;
        }

        boolean albumOrder = shouldApplyAlbumOrder(state != null ? state.getIntent() : null);
        if (albumOrder) {
            for (VideoInfo v : sortedVideos) {
                annotateAlbumTrack(v, state.getIntent());
            }
        }

        List<VideoInfo> rerankedSlice = slice.stream()
            .filter(v -> v != null && v.getBvid() != null && !v.getBvid().isBlank())
            .sorted(Comparator
                .comparingDouble((VideoInfo v) -> -scoreOf(v, rerankResults, results))
                .thenComparing((VideoInfo v) -> isPlaylistStyle(v))
                .thenComparing((VideoInfo v) -> v.getPlayCount() != null ? -v.getPlayCount() : 0L)
                .thenComparing((VideoInfo v) -> v.getCommentCount() != null ? -v.getCommentCount() : 0L)
            )
            .toList();

        if (albumOrder) {
            rerankedSlice = orderByTrackNoPreferHighScore(rerankedSlice, rerankResults, results);
        } else {
            rerankedSlice = diversifyNoAdjacentSameAuthor(rerankedSlice, rerankResults, results);
        }

        List<VideoInfo> out = new ArrayList<>(sortedVideos.size());
        out.addAll(rerankedSlice);
        for (int i = limit; i < sortedVideos.size(); i++) {
            out.add(sortedVideos.get(i));
        }

        return deduplicateByBvidPreserveOrder(out);
    }

    private boolean shouldApplyAlbumOrder(UserIntent intent) {
        if (intent == null) {
            return false;
        }
        if (intent.isAlbumOrder() && intent.getAlbumTitle() != null && !intent.getAlbumTitle().isBlank()) {
            return true;
        }
        String mode = intent.getMode();
        if (mode == null || mode.isBlank()) {
            return false;
        }
        for (String part : mode.toLowerCase().split("[,;|+]")) {
            String t = part != null ? part.trim() : "";
            if ("album_order".equals(t) || "album".equals(t)) {
                return true;
            }
        }
        return false;
    }

    private void annotateAlbumTrack(VideoInfo video, UserIntent intent) {
        if (video == null || intent == null) {
            return;
        }
        if (intent.getAlbumTitle() != null && !intent.getAlbumTitle().isBlank()
            && (video.getAlbumTitle() == null || video.getAlbumTitle().isBlank())) {
            video.setAlbumTitle(intent.getAlbumTitle());
        }
        if (video.getTrackNo() == null) {
            video.setTrackNo(extractTrackNo(video));
        }
    }

    private Integer extractTrackNo(VideoInfo video) {
        if (video == null) {
            return null;
        }
        String title = video.getTitle();
        if (title == null || title.isBlank()) {
            return null;
        }
        String t = title.trim();

        Matcher m1 = TRACK_NO_SLASH.matcher(t);
        if (m1.find()) {
            return parseTrackNo(m1.group(1));
        }
        Matcher m2 = TRACK_NO_EN.matcher(t);
        if (m2.find()) {
            return parseTrackNo(m2.group(1));
        }
        Matcher m3 = TRACK_NO_CH.matcher(t);
        if (m3.find()) {
            return parseTrackNo(m3.group(1));
        }
        Matcher m4 = TRACK_NO_PREFIX.matcher(t);
        if (m4.find()) {
            return parseTrackNo(m4.group(1));
        }
        return null;
    }

    private Integer parseTrackNo(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            if (v >= 1 && v <= 99) {
                return v;
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<VideoInfo> orderByTrackNoPreferHighScore(List<VideoInfo> reranked,
                                                          Map<String, RerankService.RerankResult> rerankResults,
                                                          Map<String, VideoRelevanceScorer.ScoringResult> results) {
        if (reranked == null || reranked.size() <= 2) {
            return reranked;
        }

        java.util.Map<Integer, VideoInfo> bestByTrack = new java.util.HashMap<>();
        java.util.Set<String> inBest = new java.util.HashSet<>();
        java.util.List<VideoInfo> noTrack = new java.util.ArrayList<>();

        for (VideoInfo v : reranked) {
            if (v == null || v.getBvid() == null || v.getBvid().isBlank()) {
                continue;
            }
            Integer tn = v.getTrackNo();
            if (tn == null) {
                tn = extractTrackNo(v);
                v.setTrackNo(tn);
            }
            if (tn == null) {
                noTrack.add(v);
                continue;
            }
            VideoInfo prev = bestByTrack.get(tn);
            if (prev == null) {
                bestByTrack.put(tn, v);
                continue;
            }
            double sPrev = scoreOf(prev, rerankResults, results);
            double sNow = scoreOf(v, rerankResults, results);
            if (sNow > sPrev) {
                bestByTrack.put(tn, v);
            } else if (sNow == sPrev) {
                long pPrev = prev.getPlayCount() != null ? prev.getPlayCount() : 0L;
                long pNow = v.getPlayCount() != null ? v.getPlayCount() : 0L;
                if (pNow > pPrev) {
                    bestByTrack.put(tn, v);
                }
            }
        }

        if (bestByTrack.isEmpty()) {
            return reranked;
        }

        java.util.List<Integer> keys = new java.util.ArrayList<>(bestByTrack.keySet());
        keys.sort(Integer::compareTo);
        java.util.List<VideoInfo> out = new java.util.ArrayList<>(reranked.size());
        for (Integer k : keys) {
            VideoInfo v = bestByTrack.get(k);
            if (v != null) {
                out.add(v);
                inBest.add(v.getBvid());
            }
        }

        for (VideoInfo v : reranked) {
            if (v == null || v.getBvid() == null || v.getBvid().isBlank()) {
                continue;
            }
            if (!inBest.contains(v.getBvid())) {
                out.add(v);
            }
        }

        return out;
    }

    private List<VideoInfo> orderByTrackNoPreferPopularity(List<VideoInfo> videos) {
        if (videos == null || videos.size() <= 2) {
            return videos;
        }
        java.util.Map<Integer, VideoInfo> bestByTrack = new java.util.HashMap<>();
        java.util.Set<String> inBest = new java.util.HashSet<>();

        for (VideoInfo v : videos) {
            if (v == null || v.getBvid() == null || v.getBvid().isBlank()) {
                continue;
            }
            Integer tn = v.getTrackNo();
            if (tn == null) {
                tn = extractTrackNo(v);
                v.setTrackNo(tn);
            }
            if (tn == null) {
                continue;
            }
            VideoInfo prev = bestByTrack.get(tn);
            if (prev == null) {
                bestByTrack.put(tn, v);
                continue;
            }
            long pPrev = prev.getPlayCount() != null ? prev.getPlayCount() : 0L;
            long pNow = v.getPlayCount() != null ? v.getPlayCount() : 0L;
            long cPrev = prev.getCommentCount() != null ? prev.getCommentCount() : 0L;
            long cNow = v.getCommentCount() != null ? v.getCommentCount() : 0L;
            if (pNow > pPrev || (pNow == pPrev && cNow > cPrev)) {
                bestByTrack.put(tn, v);
            }
        }

        if (bestByTrack.isEmpty()) {
            return videos;
        }

        java.util.List<Integer> keys = new java.util.ArrayList<>(bestByTrack.keySet());
        keys.sort(Integer::compareTo);
        java.util.List<VideoInfo> out = new java.util.ArrayList<>(videos.size());
        for (Integer k : keys) {
            VideoInfo v = bestByTrack.get(k);
            if (v != null) {
                out.add(v);
                inBest.add(v.getBvid());
            }
        }
        for (VideoInfo v : videos) {
            if (v == null || v.getBvid() == null || v.getBvid().isBlank()) {
                continue;
            }
            if (!inBest.contains(v.getBvid())) {
                out.add(v);
            }
        }
        return out;
    }

    private List<VideoInfo> diversifyNoAdjacentSameAuthor(List<VideoInfo> videos,
                                                          Map<String, RerankService.RerankResult> rerankResults,
                                                          Map<String, VideoRelevanceScorer.ScoringResult> results) {
        if (videos == null || videos.size() <= 2) {
            return videos;
        }
        List<VideoInfo> remaining = new ArrayList<>(videos);
        List<VideoInfo> out = new ArrayList<>(videos.size());
        String lastAuthor = null;
        while (!remaining.isEmpty()) {
            int bestIdx = -1;
            double bestScore = -1.0e9;
            for (int i = 0; i < remaining.size(); i++) {
                VideoInfo v = remaining.get(i);
                String author = normalizeAuthor(v != null ? v.getAuthor() : null);
                boolean ok = lastAuthor == null || author == null || !author.equals(lastAuthor);
                if (!ok) {
                    continue;
                }
                double score = scoreOf(v, rerankResults, results);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                for (int i = 0; i < remaining.size(); i++) {
                    VideoInfo v = remaining.get(i);
                    double score = scoreOf(v, rerankResults, results);
                    if (score > bestScore) {
                        bestScore = score;
                        bestIdx = i;
                    }
                }
            }
            VideoInfo picked = remaining.remove(bestIdx);
            out.add(picked);
            lastAuthor = normalizeAuthor(picked != null ? picked.getAuthor() : null);
        }
        return out;
    }

    private double scoreOf(VideoInfo v,
                           Map<String, RerankService.RerankResult> rerankResults,
                           Map<String, VideoRelevanceScorer.ScoringResult> results) {
        if (v == null || v.getBvid() == null) {
            return -1.0e9;
        }
        if (rerankResults != null) {
            RerankService.RerankResult rerank = rerankResults.get(v.getBvid());
            if (rerank != null) {
                return rerank.score();
            }
        }
        if (results != null) {
            VideoRelevanceScorer.ScoringResult r = results.get(v.getBvid());
            if (r != null) {
                return r.getScore();
            }
        }
        return -1.0e9;
    }

    private String normalizeAuthor(String author) {
        if (author == null) {
            return null;
        }
        String s = author.trim().toLowerCase();
        return s.isBlank() ? null : s;
    }

    private List<VideoInfo> deduplicateByBvidPreserveOrder(List<VideoInfo> videos) {
        if (videos == null || videos.isEmpty()) {
            return videos;
        }
        Set<String> seen = new HashSet<>();
        List<VideoInfo> out = new ArrayList<>(videos.size());
        for (VideoInfo v : videos) {
            if (v == null || v.getBvid() == null || v.getBvid().isBlank()) {
                continue;
            }
            if (seen.add(v.getBvid())) {
                out.add(v);
            }
        }
        return out;
    }

    private boolean isPlaylistStyle(VideoInfo video) {
        String title = video.getTitle();
        if (title == null) return false;
        String t = title.toLowerCase();
        return t.contains("\u5408\u96c6") || t.contains("\u6b4c\u5355") || t.contains("\u4e32\u70e7")
            || t.contains("mix") || t.contains("playlist") || t.contains("\u8fde\u64ad");
    }

    private boolean shouldHardReject(VideoInfo video, UserIntent intent) {
        StringBuilder sb = new StringBuilder();
        if (video.getTitle() != null) sb.append(video.getTitle()).append(' ');
        if (video.getTags() != null) sb.append(video.getTags()).append(' ');
        if (video.getDescription() != null) sb.append(video.getDescription()).append(' ');
        String text = sb.toString().toLowerCase();

        // 负向关键词粗过滤
        String[] negativeKeywords = {
            "\u6559\u7a0b", "\u6559\u5b66", "reaction",
            "\u526a\u8f91", "\u6df7\u526a",
            "\u96c6\u9526", "\u89e3\u8bf4", "\u8bb2\u89e3", "\u7ffb\u5531",
            "\u73b0\u573a", "live"
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
