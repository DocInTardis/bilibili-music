package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.UserPreference;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐结果解释服务
 *
 * 生成结构化的推荐解释，帮助用户理解：
 * 1. 为什么推荐这些视频
 * 2. 系统的个性化程度（偏好置信度）
 * 3. 当前推荐策略（探索/利用、冷启动）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationExplanationService {

    private final UserPreferenceService preferenceService;
    private final UserBehaviorFeedbackService behaviorFeedbackService;
    private final PreferenceConfidenceService confidenceService;
    private final PreferenceNormalizationService normalizationService;

    /**
     * 生成推荐结果解释
     */
    public PlaylistResponse.RecommendationExplanation generateExplanation(
            PlaylistContext context,
            List<VideoInfo> selectedVideos) {

        Long conversationId = context.getConversationId();
        Long userId = context.getUserId();
        UserIntent intent = context.getIntent();

        // 1. 判断是否冷启动
        boolean coldStart = behaviorFeedbackService.isColdStart(conversationId);

        // 2. 获取探索率
        double explorationRate = behaviorFeedbackService.getExplorationRate(conversationId);

        // 3. 提取主要匹配因素
        List<String> matchFactors = extractMatchFactors(intent, selectedVideos);

        // 4. 计算偏好加成详情
        Map<String, Integer> preferenceBonus = calculatePreferenceBonus(
            conversationId, userId, intent);

        // 5. 计算偏好置信度
        Double preferenceConfidence = calculatePreferenceConfidence(conversationId, userId);

        // 6. 生成摘要
        String summary = generateSummary(coldStart, explorationRate, matchFactors,
            preferenceBonus, preferenceConfidence);

        return PlaylistResponse.RecommendationExplanation.builder()
            .coldStart(coldStart)
            .explorationRate(explorationRate)
            .matchFactors(matchFactors)
            .preferenceBonus(preferenceBonus)
            .preferenceConfidence(preferenceConfidence)
            .summary(summary)
            .build();
    }

    /**
     * 提取主要匹配因素
     */
    private List<String> extractMatchFactors(UserIntent intent, List<VideoInfo> videos) {
        List<String> factors = new ArrayList<>();

        // 关键词因素
        if (intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
            factors.add("关键词: " + String.join("、", intent.getKeywords()));
        }

        // 艺人因素
        if (intent.getArtists() != null && !intent.getArtists().isEmpty()) {
            factors.add("艺人: " + String.join("、", intent.getArtists()));
        }

        // 视频数量
        factors.add("视频数量: " + videos.size());

        return factors;
    }

    /**
     * 计算偏好加成详情（归一化后的 Top-K）
     */
    private Map<String, Integer> calculatePreferenceBonus(
            Long conversationId, Long userId, UserIntent intent) {

        // 获取原始偏好权重
        Map<String, Integer> artistPrefs = userId != null
            ? preferenceService.getUserArtistPreferences(userId)
            : preferenceService.getArtistPreferences(conversationId);

        Map<String, Integer> keywordPrefs = userId != null
            ? preferenceService.getUserKeywordPreferences(userId)
            : preferenceService.getKeywordPreferences(conversationId);

        // 合并所有偏好
        Map<String, Integer> allPrefs = new HashMap<>();
        artistPrefs.forEach((k, v) -> allPrefs.put("艺人:" + k, v));
        keywordPrefs.forEach((k, v) -> allPrefs.put("关键词:" + k, v));

        // 归一化
        Map<String, Double> normalized = normalizationService.normalize(allPrefs);

        // 只返回 Top-5 偏好（乘以100便于展示）
        return normalized.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(5)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> (int) (e.getValue() * 100),
                (a, b) -> a,
                LinkedHashMap::new
            ));
    }

    /**
     * 计算用户偏好置信度
     */
    private Double calculatePreferenceConfidence(Long conversationId, Long userId) {
        // 获取所有偏好
        List<UserPreference> preferences = userId != null
            ? preferenceService.getAllUserPreferences(userId)
            : preferenceService.getAllPreferences(conversationId);

        if (preferences == null || preferences.isEmpty()) {
            return 0.0;
        }

        return confidenceService.calculateOverallConfidence(preferences);
    }

    /**
     * 生成推荐摘要
     */
    private String generateSummary(
            boolean coldStart,
            double explorationRate,
            List<String> matchFactors,
            Map<String, Integer> preferenceBonus,
            Double preferenceConfidence) {

        StringBuilder sb = new StringBuilder();

        if (coldStart) {
            sb.append("🌱 您是新用户，系统正在探索您的偏好（探索率 ")
              .append(String.format("%.0f%%", explorationRate * 100))
              .append("）。");
        } else {
            sb.append("✅ 基于您的历史偏好推荐");
            if (preferenceConfidence != null && preferenceConfidence > 0.7) {
                sb.append("（高置信度 ")
                  .append(String.format("%.0f%%", preferenceConfidence * 100))
                  .append("）");
            }
            sb.append("。");
        }

        if (!preferenceBonus.isEmpty()) {
            sb.append(" 重点关注：");
            String topPrefs = preferenceBonus.entrySet().stream()
                .limit(3)
                .map(e -> e.getKey())
                .collect(Collectors.joining("、"));
            sb.append(topPrefs);
        }

        return sb.toString();
    }
}
