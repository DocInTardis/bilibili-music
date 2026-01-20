package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.VideoInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyRecommendationService {

    private static final int DEFAULT_FETCH_LIMIT = 50;

    private final ObjectMapper objectMapper;
    private final UserPreferenceService preferenceService;
    private final DatabaseService databaseService;

    @Value("${bilibili.daily-rank-url:https://api.bilibili.com/x/web-interface/ranking/v2?rid=3&type=all}")
    private String dailyRankUrl;

    @Value("${bilibili.daily-rank-timeout-ms:8000}")
    private long dailyRankTimeoutMs;

    @Value("${bilibili.daily-rank-cache-seconds:600}")
    private long cacheSeconds;

    @Value("${recommend.daily.personal-weight:0.7}")
    private double personalWeight;

    @Value("${recommend.daily.rank-weight:0.3}")
    private double rankWeight;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(2000))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private volatile CacheEntry cache;

    public List<VideoInfo> getDailyRecommendations(Long conversationId, int limit) {
        int target = Math.max(1, limit);
        List<VideoInfo> ranking = loadDailyRanking(Math.max(target, DEFAULT_FETCH_LIMIT));
        if (ranking.isEmpty()) {
            return databaseService.getRandomRecommendations(target);
        }
        Long resolvedConversationId = conversationId;
        if (resolvedConversationId == null) {
            resolvedConversationId = databaseService.getOrCreateActiveConversation().getId();
        }

        Map<String, Integer> artistWeights = preferenceService.getArtistPreferences(resolvedConversationId);
        Map<String, Integer> keywordWeights = preferenceService.getKeywordPreferences(resolvedConversationId);

        List<ScoredVideo> scored = new ArrayList<>();
        int total = ranking.size();
        for (int i = 0; i < ranking.size(); i++) {
            VideoInfo video = ranking.get(i);
            double rankScore = total > 1 ? (double) (total - i) / (double) total : 1.0;
            double personalScore = computePersonalScore(video, artistWeights, keywordWeights);
            double personalNormalized = Math.tanh(personalScore / 8.0);
            double finalScore = personalNormalized * personalWeight + rankScore * rankWeight;
            scored.add(new ScoredVideo(video, finalScore));
        }

        scored.sort(Comparator.comparingDouble(ScoredVideo::score).reversed());
        return weightedSample(scored, target, resolvedConversationId);
    }

    private List<VideoInfo> loadDailyRanking(int limit) {
        CacheEntry snapshot = cache;
        LocalDate today = LocalDate.now();
        long now = System.currentTimeMillis();
        if (snapshot != null && snapshot.date().equals(today)
            && (now - snapshot.fetchedAtMs()) < cacheSeconds * 1000L) {
            return snapshot.videos();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(dailyRankUrl))
                .timeout(Duration.ofMillis(Math.max(1000L, dailyRankTimeoutMs)))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[DailyRank] fetch failed status={}", response.statusCode());
                return Collections.emptyList();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode list = root.path("data").path("list");
            if (!list.isArray()) {
                return Collections.emptyList();
            }
            List<VideoInfo> videos = new ArrayList<>();
            for (JsonNode item : list) {
                if (videos.size() >= limit) {
                    break;
                }
                String bvid = text(item, "bvid");
                if (bvid == null || bvid.isBlank()) {
                    continue;
                }
                String title = text(item, "title");
                String author = text(item.path("owner"), "name");
                String cover = normalizeCoverUrl(text(item, "pic"));
                String duration = formatDuration(item.path("duration").asInt(0));
                String tags = text(item, "tname");
                String desc = text(item, "desc");
                String url = "https://www.bilibili.com/video/" + bvid;
                videos.add(VideoInfo.builder()
                    .bvid(bvid)
                    .title(title != null ? title : "未命名视频")
                    .author(author != null ? author : "未知作者")
                    .url(url)
                    .duration(duration)
                    .tags(tags)
                    .description(desc)
                    .coverUrl(cover)
                    .build());
            }
            cache = new CacheEntry(today, now, videos);
            return videos;
        } catch (Exception e) {
            log.warn("[DailyRank] fetch failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private double computePersonalScore(VideoInfo video,
                                        Map<String, Integer> artistWeights,
                                        Map<String, Integer> keywordWeights) {
        if (video == null) {
            return 0.0;
        }
        double score = 0.0;
        String author = video.getAuthor();
        if (author != null && artistWeights != null) {
            Integer w = artistWeights.get(author);
            if (w != null) {
                score += w * 1.5;
            }
        }
        String corpus = buildCorpus(video);
        if (!corpus.isEmpty() && keywordWeights != null) {
            for (Map.Entry<String, Integer> entry : keywordWeights.entrySet()) {
                String keyword = entry.getKey();
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                if (corpus.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score += entry.getValue();
                }
            }
        }
        return score;
    }

    private String buildCorpus(VideoInfo video) {
        StringBuilder sb = new StringBuilder();
        if (video.getTitle() != null) {
            sb.append(video.getTitle()).append(' ');
        }
        if (video.getTags() != null) {
            sb.append(video.getTags()).append(' ');
        }
        if (video.getDescription() != null) {
            sb.append(video.getDescription()).append(' ');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private List<VideoInfo> weightedSample(List<ScoredVideo> scored, int limit, Long conversationId) {
        if (scored.isEmpty()) {
            return Collections.emptyList();
        }
        long seed = LocalDate.now().toEpochDay();
        if (conversationId != null) {
            seed = seed * 31 + conversationId;
        }
        Random random = new Random(seed);
        List<ScoredVideo> pool = new ArrayList<>(scored);
        List<VideoInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        while (!pool.isEmpty() && result.size() < limit) {
            double totalWeight = 0.0;
            for (ScoredVideo item : pool) {
                totalWeight += weight(item.score());
            }
            double pick = random.nextDouble() * totalWeight;
            double acc = 0.0;
            ScoredVideo chosen = pool.get(0);
            for (ScoredVideo item : pool) {
                acc += weight(item.score());
                if (acc >= pick) {
                    chosen = item;
                    break;
                }
            }
            pool.remove(chosen);
            VideoInfo video = chosen.video();
            if (video != null && seen.add(video.getBvid())) {
                result.add(video);
            }
        }
        return result;
    }

    private double weight(double score) {
        double base = score + 1.2;
        return Math.max(0.05, base);
    }

    private String formatDuration(int seconds) {
        if (seconds <= 0) {
            return "未知";
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private String normalizeCoverUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String clean = url.trim();
        if (clean.startsWith("//")) {
            clean = "https:" + clean;
        }
        int atIdx = clean.indexOf('@');
        if (atIdx > 0) {
            clean = clean.substring(0, atIdx);
        }
        return clean;
    }

    private record CacheEntry(LocalDate date, long fetchedAtMs, List<VideoInfo> videos) {
    }

    private record ScoredVideo(VideoInfo video, double score) {
    }
}
