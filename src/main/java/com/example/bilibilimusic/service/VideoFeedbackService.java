package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.VideoFeedbackResponse;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.UserBehaviorEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoFeedbackService {

    private static final long FEEDBACK_TIMEOUT_MS = 12_000L;

    private final OllamaService ollamaService;
    private final PromptVersionService promptVersionService;
    private final UserPreferenceService preferenceService;
    private final UserBehaviorFeedbackService behaviorFeedbackService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VideoFeedbackResponse handleFeedback(Long conversationId,
                                                Long userId,
                                                VideoInfo video,
                                                String comment) {
        FeedbackAnalysis analysis = analyzeFeedback(video, comment);
        Map<String, Integer> appliedArtists = new LinkedHashMap<>();
        Map<String, Integer> appliedKeywords = new LinkedHashMap<>();

        int sign = sentimentSign(analysis.sentiment);
        int artistDelta = sign == 0 ? 0 : Math.max(1, Math.round(2 + (float) analysis.intensity * 3));
        int keywordDelta = sign == 0 ? 0 : Math.max(1, Math.round(1 + (float) analysis.intensity * 2));

        List<String> artists = normalizeList(analysis.artists);
        List<String> keywords = normalizeList(analysis.keywords);

        if (artists.isEmpty() && video != null && video.getAuthor() != null && !video.getAuthor().isBlank()) {
            artists = List.of(video.getAuthor().trim());
        }

        if (sign != 0 && conversationId != null && video != null && video.getBvid() != null && !video.getBvid().isBlank()) {
            try {
                UserBehaviorEvent.BehaviorType type = sign > 0
                    ? UserBehaviorEvent.BehaviorType.LIKE
                    : UserBehaviorEvent.BehaviorType.DISLIKE;
                UserBehaviorEvent event = UserBehaviorEvent.builder()
                    .conversationId(conversationId)
                    .behaviorType(type)
                    .targetType("video")
                    .targetId(video.getBvid())
                    .intensity(Math.max(0.1, Math.min(1.0, analysis.intensity)))
                    .contextJson(buildContextJson(video, comment))
                    .occurredAt(LocalDateTime.now())
                    .applied(false)
                    .build();
                behaviorFeedbackService.recordBehavior(event);
            } catch (Exception e) {
                log.debug("[Feedback] record behavior failed: {}", e.getMessage());
            }
        }

        if (conversationId != null && sign != 0) {
            for (String artist : artists) {
                if (artist == null || artist.isBlank()) {
                    continue;
                }
                int delta = sign * artistDelta;
                preferenceService.adjustPreference(conversationId, "artist", artist.trim(), delta);
                appliedArtists.put(artist.trim(), delta);
            }
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                int delta = sign * keywordDelta;
                preferenceService.adjustPreference(conversationId, "keyword", keyword.trim().toLowerCase(Locale.ROOT), delta);
                appliedKeywords.put(keyword.trim(), delta);
            }
        }

        String reply = analysis.reply;
        if (reply == null || reply.isBlank()) {
            reply = buildFallbackReply(video, analysis, artists, keywords);
        }

        return VideoFeedbackResponse.builder()
            .reply(reply)
            .sentiment(analysis.sentiment)
            .intensity(analysis.intensity)
            .artists(artists)
            .keywords(keywords)
            .appliedArtists(appliedArtists)
            .appliedKeywords(appliedKeywords)
            .build();
    }

    private FeedbackAnalysis analyzeFeedback(VideoInfo video, String comment) {
        String systemPrompt = promptVersionService.getPromptTemplate("video_feedback");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = defaultPrompt();
        }
        String userPrompt = buildUserPrompt(video, comment);
        String content = null;
        try {
            content = ollamaService.chat("video_feedback", systemPrompt, userPrompt, false, FEEDBACK_TIMEOUT_MS);
        } catch (Exception e) {
            log.debug("[Feedback] LLM failed: {}", e.getMessage());
        }

        FeedbackAnalysis analysis = parseAnalysis(content);
        if (analysis != null) {
            return normalizeAnalysis(analysis);
        }
        return fallbackAnalysis(video, comment);
    }

    private String buildUserPrompt(VideoInfo video, String comment) {
        StringBuilder sb = new StringBuilder();
        if (video != null) {
            if (video.getTitle() != null && !video.getTitle().isBlank()) {
                sb.append("视频标题: ").append(video.getTitle()).append('\n');
            }
            if (video.getAuthor() != null && !video.getAuthor().isBlank()) {
                sb.append("作者: ").append(video.getAuthor()).append('\n');
            }
            if (video.getTags() != null && !video.getTags().isBlank()) {
                sb.append("标签: ").append(video.getTags()).append('\n');
            }
        }
        sb.append("用户评价: ").append(comment);
        return sb.toString();
    }

    private FeedbackAnalysis parseAnalysis(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            String json = content.substring(start, end + 1);
            return objectMapper.readValue(json, FeedbackAnalysis.class);
        } catch (Exception e) {
            return null;
        }
    }

    private FeedbackAnalysis normalizeAnalysis(FeedbackAnalysis analysis) {
        if (analysis.sentiment == null || analysis.sentiment.isBlank()) {
            analysis.sentiment = "neutral";
        }
        analysis.sentiment = analysis.sentiment.trim().toLowerCase(Locale.ROOT);
        if (analysis.intensity < 0.0) {
            analysis.intensity = 0.0;
        } else if (analysis.intensity > 1.0) {
            analysis.intensity = 1.0;
        }
        if (analysis.artists == null) {
            analysis.artists = Collections.emptyList();
        }
        if (analysis.keywords == null) {
            analysis.keywords = Collections.emptyList();
        }
        return analysis;
    }

    private FeedbackAnalysis fallbackAnalysis(VideoInfo video, String comment) {
        String normalized = comment != null ? comment.trim().toLowerCase(Locale.ROOT) : "";
        String sentiment = "neutral";
        if (containsAny(normalized, "不喜欢", "讨厌", "难听", "一般", "不好", "太吵", "拉胯", "踩雷")) {
            sentiment = "negative";
        } else if (containsAny(normalized, "喜欢", "好听", "不错", "爱了", "上头", "神曲", "宝藏", "收藏", "循环")) {
            sentiment = "positive";
        }
        FeedbackAnalysis analysis = new FeedbackAnalysis();
        analysis.sentiment = sentiment;
        analysis.intensity = "neutral".equals(sentiment) ? 0.3 : 0.7;
        analysis.artists = video != null && video.getAuthor() != null ? List.of(video.getAuthor()) : Collections.emptyList();
        analysis.keywords = Collections.emptyList();
        analysis.reply = buildFallbackReply(video, analysis, analysis.artists, analysis.keywords);
        return analysis;
    }

    private String buildFallbackReply(VideoInfo video, FeedbackAnalysis analysis, List<String> artists, List<String> keywords) {
        String title = video != null && video.getTitle() != null && !video.getTitle().isBlank()
            ? video.getTitle()
            : "这首歌";
        String sentimentText = switch (analysis.sentiment) {
            case "positive" -> "喜欢";
            case "negative" -> "不太喜欢";
            default -> "一般";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("已记录你对《").append(title).append("》的看法：").append(sentimentText).append("。");
        if (!artists.isEmpty()) {
            sb.append("已更新偏好艺人：").append(String.join(" / ", artists)).append("。");
        }
        if (!keywords.isEmpty()) {
            sb.append("已更新偏好关键词：").append(String.join(" / ", keywords)).append("。");
        }
        return sb.toString();
    }

    private int sentimentSign(String sentiment) {
        if (sentiment == null) {
            return 0;
        }
        return switch (sentiment.toLowerCase(Locale.ROOT)) {
            case "positive", "like", "love" -> 1;
            case "negative", "dislike", "hate" -> -1;
            default -> 0;
        };
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String buildContextJson(VideoInfo video, String comment) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (video != null) {
            ctx.put("title", video.getTitle());
            ctx.put("author", video.getAuthor());
            ctx.put("bvid", video.getBvid());
            ctx.put("url", video.getUrl());
        }
        ctx.put("comment", comment);
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> normalizeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String item : list) {
            if (item == null) {
                continue;
            }
            String t = item.trim();
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private String defaultPrompt() {
        return """
            你是音乐偏好分析助手，请严格输出 JSON。
            任务：根据用户对单个视频的评价，提取情绪、强度、艺人、关键词，并给出简短回应。

            输出格式：
            {
              "sentiment": "positive|negative|neutral",
              "intensity": 0.0,
              "artists": ["艺人"],
              "keywords": ["关键词"],
              "reply": "给用户的简短回应"
            }

            约束：
            - 只输出 JSON，不要额外文本
            - intensity 范围 0~1
            - artists/keywords 可为空数组
            """.trim();
    }

    @Data
    private static class FeedbackAnalysis {
        private String sentiment;
        private double intensity;
        private List<String> artists;
        private List<String> keywords;
        private String reply;
    }
}
