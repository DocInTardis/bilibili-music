package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MultiRecallService {

    public static final String RECALL_QUERY = "query";
    public static final String RECALL_KEYWORD = "keyword";
    public static final String RECALL_PREF_ARTIST = "pref_artist";
    public static final String RECALL_PREF_KEYWORD = "pref_keyword";
    public static final String RECALL_DAILY_RANK = "daily_rank";
    public static final String RECALL_FALLBACK_DB = "fallback_db";

    private final BilibiliSearchService searchService;
    private final DailyRecommendationService dailyRecommendationService;
    private final UserPreferenceService preferenceService;
    private final CacheService cacheService;
    private final DatabaseService databaseService;

    @Value("${recommend.recall.per-query-limit:20}")
    private int perQueryLimit;

    @Value("${recommend.recall.max-candidates-multiplier:3}")
    private int maxCandidatesMultiplier;

    @Value("${recommend.recall.enable-preference:true}")
    private boolean enablePreference;

    @Value("${recommend.recall.enable-daily:true}")
    private boolean enableDaily;

    @Value("${recommend.recall.preference-artist-top:2}")
    private int preferenceArtistTop;

    @Value("${recommend.recall.preference-keyword-top:2}")
    private int preferenceKeywordTop;

    @Value("${recommend.recall.min-pref-trigger:20}")
    private int minPrefTrigger;

    @Value("${recommend.recall.daily-limit:20}")
    private int dailyLimit;

    public RecallResult recall(PlaylistContext context) {
        if (context == null || context.getIntent() == null) {
            return new RecallResult(Collections.emptyList(), Collections.emptyMap(), Collections.emptyList());
        }

        UserIntent intent = context.getIntent();
        int target = intent.getLimit() > 0 ? intent.getLimit() : 20;
        int maxCandidates = Math.max(target * Math.max(1, maxCandidatesMultiplier), perQueryLimit);
        int queryLimit = Math.max(perQueryLimit, Math.max(8, target));

        Map<String, VideoInfo> merged = new LinkedHashMap<>();
        Map<String, Integer> channelCounts = new LinkedHashMap<>();
        List<String> recallQueries = new ArrayList<>();
        Set<String> usedQueries = new LinkedHashSet<>();

        String rawQuery = sanitize(intent.getQuery());
        String keywordQuery = sanitize(buildKeywordQuery(intent));

        if (!rawQuery.isBlank()) {
            recallQueries.add(rawQuery);
            usedQueries.add(rawQuery);
            mergeChannel(RECALL_QUERY, fetchSearch(rawQuery, queryLimit), merged, channelCounts);
        }

        if (!keywordQuery.isBlank() && !keywordQuery.equalsIgnoreCase(rawQuery)) {
            recallQueries.add(keywordQuery);
            usedQueries.add(keywordQuery);
            mergeChannel(RECALL_KEYWORD, fetchSearch(keywordQuery, queryLimit), merged, channelCounts);
        }

        if (enablePreference && shouldRecallMore(merged.size(), target)) {
            Long conversationId = context.getConversationId();
            Long userId = context.getUserId();
            Map<String, Integer> artistPrefs = userId != null
                ? preferenceService.getUserArtistPreferences(userId)
                : preferenceService.getArtistPreferences(conversationId);
            Map<String, Integer> keywordPrefs = userId != null
                ? preferenceService.getUserKeywordPreferences(userId)
                : preferenceService.getKeywordPreferences(conversationId);

            for (String artist : topKeys(artistPrefs, preferenceArtistTop)) {
                String q = sanitize(artist);
                if (q.isBlank() || usedQueries.contains(q)) {
                    continue;
                }
                usedQueries.add(q);
                recallQueries.add(q);
                mergeChannel(RECALL_PREF_ARTIST, fetchSearch(q, Math.max(6, queryLimit / 2)), merged, channelCounts);
                if (!shouldRecallMore(merged.size(), target)) {
                    break;
                }
            }

            for (String keyword : topKeys(keywordPrefs, preferenceKeywordTop)) {
                String q = sanitize(keyword);
                if (q.isBlank() || usedQueries.contains(q)) {
                    continue;
                }
                usedQueries.add(q);
                recallQueries.add(q);
                mergeChannel(RECALL_PREF_KEYWORD, fetchSearch(q, Math.max(6, queryLimit / 2)), merged, channelCounts);
                if (!shouldRecallMore(merged.size(), target)) {
                    break;
                }
            }
        }

        if (enableDaily && shouldRecallMore(merged.size(), target)) {
            List<VideoInfo> daily = dailyRecommendationService.getDailyRecommendations(
                context.getConversationId(), Math.max(dailyLimit, target));
            mergeChannel(RECALL_DAILY_RANK, daily, merged, channelCounts);
        }

        if (merged.isEmpty()) {
            List<VideoInfo> fallback = databaseService.getRandomRecommendations(target);
            mergeChannel(RECALL_FALLBACK_DB, fallback, merged, channelCounts);
        }

        List<VideoInfo> out = new ArrayList<>(merged.values());
        if (out.size() > maxCandidates) {
            out = out.subList(0, maxCandidates);
        }

        return new RecallResult(out, channelCounts, recallQueries);
    }

    private List<VideoInfo> fetchSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        List<VideoInfo> cached = cacheService.getCachedSearchResults(query);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        List<VideoInfo> results = searchService.search(query, limit);
        if (results != null && !results.isEmpty()) {
            cacheService.cacheSearchResults(query, results);
        }
        return results != null ? results : Collections.emptyList();
    }

    private void mergeChannel(String channel,
                              List<VideoInfo> videos,
                              Map<String, VideoInfo> merged,
                              Map<String, Integer> channelCounts) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        int added = 0;
        for (VideoInfo video : videos) {
            if (video == null) {
                continue;
            }
            String key = buildKey(video);
            if (key.isBlank()) {
                continue;
            }
            VideoInfo existing = merged.get(key);
            if (existing == null) {
                addRecallSource(video, channel);
                merged.put(key, video);
                added++;
            } else {
                mergeVideo(existing, video);
                addRecallSource(existing, channel);
            }
        }
        if (added > 0) {
            channelCounts.merge(channel, added, Integer::sum);
        }
    }

    private void mergeVideo(VideoInfo target, VideoInfo incoming) {
        if (target == null || incoming == null) {
            return;
        }
        if (isBlank(target.getTitle()) && !isBlank(incoming.getTitle())) {
            target.setTitle(incoming.getTitle());
        }
        if (isBlank(target.getAuthor()) && !isBlank(incoming.getAuthor())) {
            target.setAuthor(incoming.getAuthor());
        }
        if (isBlank(target.getDuration()) && !isBlank(incoming.getDuration())) {
            target.setDuration(incoming.getDuration());
        }
        if (isBlank(target.getTags()) && !isBlank(incoming.getTags())) {
            target.setTags(incoming.getTags());
        }
        if (isBlank(target.getDescription()) && !isBlank(incoming.getDescription())) {
            target.setDescription(incoming.getDescription());
        }
        if (isBlank(target.getCoverUrl()) && !isBlank(incoming.getCoverUrl())) {
            target.setCoverUrl(incoming.getCoverUrl());
        }
        if (target.getPlayCount() == null && incoming.getPlayCount() != null) {
            target.setPlayCount(incoming.getPlayCount());
        }
        if (target.getCommentCount() == null && incoming.getCommentCount() != null) {
            target.setCommentCount(incoming.getCommentCount());
        }
    }

    private void addRecallSource(VideoInfo video, String source) {
        if (video == null || source == null || source.isBlank()) {
            return;
        }
        List<String> sources = video.getRecallSources();
        if (sources == null) {
            sources = new ArrayList<>();
            video.setRecallSources(sources);
        }
        if (!sources.contains(source)) {
            sources.add(source);
        }
    }

    private String buildKey(VideoInfo video) {
        if (video == null) {
            return "";
        }
        if (!isBlank(video.getBvid())) {
            return "bvid:" + video.getBvid().trim();
        }
        if (!isBlank(video.getUrl())) {
            return "url:" + video.getUrl().trim();
        }
        return "";
    }

    private List<String> topKeys(Map<String, Integer> weights, int top) {
        if (weights == null || weights.isEmpty() || top <= 0) {
            return Collections.emptyList();
        }
        return weights.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(top)
            .map(Map.Entry::getKey)
            .filter(v -> v != null && !v.isBlank())
            .collect(Collectors.toList());
    }

    private String buildKeywordQuery(UserIntent intent) {
        if (intent == null) {
            return "";
        }
        List<String> keywords = intent.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }
        return String.join(" ", keywords);
    }

    private boolean shouldRecallMore(int currentSize, int target) {
        int threshold = Math.max(minPrefTrigger, target);
        return currentSize < threshold;
    }

    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record RecallResult(List<VideoInfo> videos,
                               Map<String, Integer> channelCounts,
                               List<String> recallQueries) {
    }
}
