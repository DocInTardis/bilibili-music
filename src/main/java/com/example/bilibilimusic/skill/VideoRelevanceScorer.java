package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.UserPreference;
import com.example.bilibilimusic.service.UserBehaviorFeedbackService;
import com.example.bilibilimusic.service.UserPreferenceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 视频相关性评分器
 * 
 * 核心思想：准确性 = （更好的候选）×（更准的相关性判断）×（更懂用户）×（更少误判）
 * 
 * 新增：完整的"行为 → 偏好 → 决策"闭环
 * 1. 行为建模：记录用户的点赞、跳过、播放完成度等行为
 * 2. 权重衰减：偏好权重随时间指数衰减（半衰期策略）
 * 3. 反馈强化：冷启动探索 + ε-greedy 平衡探索与利用
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoRelevanceScorer {
    
    private final UserBehaviorFeedbackService behaviorFeedbackService;
    private final UserPreferenceService preferenceService;
    
    // ==================== 负关键词（黑名单） ====================
    
    private static final List<String> NEGATIVE_KEYWORDS = Arrays.asList(
        "教学", "教程", "reaction", "反应", "react",
        "解析", "讲解", "采访", "访谈", "专访",
        "剪辑", "混剪", "盘点", "解说", "赏析",
        "翻唱教学", "教你", "如何", "怎么",
        "vlog", "日常", "开箱", "测评"
    );
    
    // 时长异常阈值（毫秒）
    private static final long DURATION_TOO_LONG_MS = 10 * 60 * 1000; // 10分钟
    private static final long DURATION_TOO_SHORT_MS = 30 * 1000;      // 30秒
    
    // 合集/串烧关键词
    private static final List<String> COLLECTION_KEYWORDS = Arrays.asList(
        "合集", "串烧", "歌单", "精选", "集锦",
        "mix", "mixtape", "playlist", "compilation"
    );
    
    // 合作关键词
    private static final List<String> COLLABORATION_KEYWORDS = Arrays.asList(
        "feat", "ft", "featuring", "合作", "vs", "&"
    );
    
    /**
     * 计算视频相关性分数
     * 
     * @param video 视频信息
     * @param intent 用户意图
     * @return 评分结果
     */
    public ScoringResult scoreVideo(VideoInfo video, UserIntent intent) {
        return scoreVideo(video, intent, null, null, null);
    }
    
    /**
     * 计算视频相关性分数（含偏好权重）
     * 
     * @param video 视频信息
     * @param intent 用户意图
     * @param artistPrefs 艺人偏好权重
     * @param keywordPrefs 关键词偏好权重
     * @return 评分结果
     */
    public ScoringResult scoreVideo(VideoInfo video, UserIntent intent, 
                                    Map<String, Integer> artistPrefs, 
                                    Map<String, Integer> keywordPrefs) {
        return scoreVideo(video, intent, artistPrefs, keywordPrefs, null);
    }
    
    /**
     * 计算视频相关性分数（完整闭环版本）
     * 
     * 支持：
     * 1. 基础相关性评分
     * 2. 偏好权重加成（含衰减）
     * 3. 探索-利用平衡（冷启动策略）
     * 
     * @param video 视频信息
     * @param intent 用户意图
     * @param artistPrefs 艺人偏好权重
     * @param keywordPrefs 关键词偏好权重
     * @param conversationId 会话 ID（用于冷启动判断）
     * @return 评分结果
     */
    public ScoringResult scoreVideo(VideoInfo video, UserIntent intent, 
                                    Map<String, Integer> artistPrefs, 
                                    Map<String, Integer> keywordPrefs,
                                    Long conversationId) {
        ScoringResult result = new ScoringResult();
        result.setVideo(video);
            
        ScoringFeatures features = new ScoringFeatures();
        result.setFeatures(features);
                    
        int totalScore = 0;
        List<String> reasons = new ArrayList<>();
        
        String mode = intent != null ? intent.getMode() : null;
        java.util.Set<String> modeTags = parseModeTags(mode);
        boolean strict = modeTags.contains("strict");
        boolean explore = modeTags.contains("explore");
        result.setAcceptThreshold(strict ? 5 : 0);
                    
        // 1. 负关键词过滤（优先级最高，直接拒绝）
        if (containsNegativeKeywords(video)) {
            features.setNegativeKeywordHit(true);
            result.setScore(-100);
            result.setReason("包含负关键词，直接拒绝");
            result.setReject(true);
            return result;
        }
            
        // 2. 标题命中关键词 (+5 per keyword, +偏好权重)
        int titleScore = scoreTitleMatch(video.getTitle(), intent.getKeywords(), keywordPrefs);
        features.setTitleScore(titleScore);
        totalScore += titleScore;
        if (titleScore > 0) {
            reasons.add(String.format("标题命中关键词: +%d", titleScore));
        }
            
        // 3. 作者命中关键词 (+4 per artist, +偏好权重)
        int authorScore = scoreAuthorMatch(video.getAuthor(), intent, artistPrefs);
        features.setAuthorScore(authorScore);
        totalScore += authorScore;
        if (authorScore > 0) {
            reasons.add(String.format("作者匹配: +%d", authorScore));
        }
            
        // 4. 标签命中 (+3 per tag, +偏好权重)
        int tagScore = scoreTagMatch(video.getTags(), intent.getKeywords(), keywordPrefs);
        features.setTagScore(tagScore);
        totalScore += tagScore;
        if (tagScore > 0) {
            reasons.add(String.format("标签匹配: +%d", tagScore));
        }
            
        // 5. 描述命中 (+1 per keyword, +偏好权重)
        int descScore = scoreDescriptionMatch(video.getDescription(), intent.getKeywords(), keywordPrefs);
        features.setDescriptionScore(descScore);
        totalScore += descScore;
        if (descScore > 0) {
            reasons.add(String.format("描述匹配: +%d", descScore));
        }
            
        // 6. 单一艺人 (+2)
        if (isSingleArtist(video)) {
            totalScore += 2;
            features.setSingleArtistBonus(2);
            reasons.add("单一艺人: +2");
        }
            
        // 7. 合作视频 (根据用户偏好决定)
        if (isCollaboration(video)) {
            if (intent.isSingleArtistOnly()) {
                totalScore -= 3;
                features.setCollaborationAdjust(-3);
                reasons.add("合作视频（用户要求单一艺人）: -3");
            } else {
                totalScore += 2;
                features.setCollaborationAdjust(2);
                reasons.add("合作视频: +2");
            }
        }
            
        // 8. 合集/串烧 (-3)
        if (isCollection(video)) {
            totalScore -= 3;
            features.setCollectionPenalty(-3);
            reasons.add("合集/串烧: -3");
        }
            
        // 9. 时长异常 (-2)
        int durationPenalty = scoreDuration(video.getDuration());
        features.setDurationPenalty(durationPenalty);
        if (durationPenalty < 0) {
            totalScore += durationPenalty;
            reasons.add(String.format("时长异常: %d", durationPenalty));
        }
            
        // 10. 可信度加分（播放量、评论数）
        int credibilityScore = scoreCredibility(video);
        features.setCredibilityScore(credibilityScore);
        totalScore += credibilityScore;
        if (credibilityScore > 0) {
            reasons.add(String.format("可信度: +%d", credibilityScore));
        }
            
        // 11. 新增：探索加成（冷启动策略）
        if (conversationId != null) {
            boolean isNewVideo = !hasPreference(video.getBvid(), conversationId);
            double explorationBonus = behaviorFeedbackService.getExplorationBonus(isNewVideo, conversationId);
                        
            if (explorationBonus > 0) {
                if (explore) {
                    explorationBonus *= 1.5;
                }
                int bonusInt = (int) explorationBonus;
                totalScore += bonusInt;
                features.setExplorationBonus(bonusInt);
                reasons.add(String.format("探索加成: +%.0f", explorationBonus));
            }
        }
                    
        result.setScore(totalScore);
        result.setReason(String.join("; ", reasons));
        result.setReject(totalScore < 0); // 负分直接拒绝
            
        return result;
    }
    
    /**
     * 判断是否对该视频有偏好记录
     */
    private boolean hasPreference(String bvid, Long conversationId) {
        if (bvid == null || conversationId == null) {
            return false;
        }
        
        Map<String, Integer> prefs = preferenceService.getPreferenceWeights(conversationId);
        String key = "video:" + bvid;
        return prefs.containsKey(key);
    }
    
    private java.util.Set<String> parseModeTags(String mode) {
        if (mode == null || mode.isBlank()) {
            return java.util.Collections.emptySet();
        }
        return java.util.Arrays.stream(mode.toLowerCase().split("[,;|+]"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
    }
            
    /**
     * 检查是否包含负关键词
     */
    private boolean containsNegativeKeywords(VideoInfo video) {
        String title = video.getTitle() != null ? video.getTitle().toLowerCase() : "";
        String desc = video.getDescription() != null ? video.getDescription().toLowerCase() : "";
        String combined = title + " " + desc;
        
        for (String negative : NEGATIVE_KEYWORDS) {
            if (combined.contains(negative.toLowerCase())) {
                log.debug("视频包含负关键词 '{}': {}", negative, video.getTitle());
                return true;
            }
        }
        return false;
    }
    
    /**
     * 标题匹配评分（含偏好加成）
     */
    private int scoreTitleMatch(String title, List<String> keywords, Map<String, Integer> keywordPrefs) {
        if (title == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        String lowerTitle = title.toLowerCase();
        int score = 0;
        
        for (String keyword : keywords) {
            if (lowerTitle.contains(keyword.toLowerCase())) {
                score += 5;
                
                // 偏好加成
                if (keywordPrefs != null) {
                    Integer prefWeight = keywordPrefs.get(keyword.toLowerCase());
                    if (prefWeight != null) {
                        score += prefWeight;
                        log.debug("[标题匹配] 关键词偏好加成: {} (+{})", keyword, prefWeight);
                    }
                }
            }
        }
        
        return score;
    }
    
    /**
     * 标题匹配评分（不含偏好）
     */
    private int scoreTitleMatch(String title, List<String> keywords) {
        if (title == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        String lowerTitle = title.toLowerCase();
        int score = 0;
        
        for (String keyword : keywords) {
            if (lowerTitle.contains(keyword.toLowerCase())) {
                score += 5;
            }
        }
        
        return score;
    }
    
    /**
     * 作者匹配评分（含偏好加成）
     */
    private int scoreAuthorMatch(String author, UserIntent intent, Map<String, Integer> artistPrefs) {
        if (author == null) {
            return 0;
        }
        
        String lowerAuthor = author.toLowerCase();
        int score = 0;
        
        // 匹配意图中的艺人
        if (intent.getArtists() != null) {
            for (String artist : intent.getArtists()) {
                if (lowerAuthor.contains(artist.toLowerCase())) {
                    score += 4;
                }
            }
        }
        
        // 偏好加成
        if (artistPrefs != null) {
            for (Map.Entry<String, Integer> entry : artistPrefs.entrySet()) {
                if (lowerAuthor.contains(entry.getKey().toLowerCase())) {
                    score += entry.getValue();
                    log.debug("[作者匹配] 艺人偏好加成: {} (+{})", entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
        
        return score;
    }
    
    /**
     * 作者匹配评分（不含偏好）
     */
    private int scoreAuthorMatch(String author, UserIntent intent) {
        if (author == null || intent.getArtists() == null || intent.getArtists().isEmpty()) {
            return 0;
        }
        
        String lowerAuthor = author.toLowerCase();
        int score = 0;
        
        for (String artist : intent.getArtists()) {
            if (lowerAuthor.contains(artist.toLowerCase())) {
                score += 4;
            }
        }
        
        return score;
    }
    
    /**
     * 标签匹配评分（含偏好加成）
     */
    private int scoreTagMatch(String tags, List<String> keywords, Map<String, Integer> keywordPrefs) {
        if (tags == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        String lowerTags = tags.toLowerCase();
        int score = 0;
        
        for (String keyword : keywords) {
            if (lowerTags.contains(keyword.toLowerCase())) {
                score += 3;
                
                // 偏好加成
                if (keywordPrefs != null) {
                    Integer prefWeight = keywordPrefs.get(keyword.toLowerCase());
                    if (prefWeight != null) {
                        score += prefWeight;
                        log.debug("[标签匹配] 关键词偏好加成: {} (+{})", keyword, prefWeight);
                    }
                }
            }
        }
        
        return score;
    }
    
    /**
     * 标签匹配评分（不含偏好）
     */
    private int scoreTagMatch(String tags, List<String> keywords) {
        if (tags == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        String lowerTags = tags.toLowerCase();
        int score = 0;
        
        for (String keyword : keywords) {
            if (lowerTags.contains(keyword.toLowerCase())) {
                score += 3;
            }
        }
        
        return score;
    }
    
    /**
     * 描述匹配评分（含偏好加成）
     */
    private int scoreDescriptionMatch(String description, List<String> keywords, Map<String, Integer> keywordPrefs) {
        if (description == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        String lowerDesc = description.toLowerCase();
        int score = 0;
        
        for (String keyword : keywords) {
            if (lowerDesc.contains(keyword.toLowerCase())) {
                score += 1;
                
                // 偏好加成
                if (keywordPrefs != null) {
                    Integer prefWeight = keywordPrefs.get(keyword.toLowerCase());
                    if (prefWeight != null) {
                        score += prefWeight;
                        log.debug("[描述匹配] 关键词偏好加成: {} (+{})", keyword, prefWeight);
                    }
                }
            }
        }
        
        return score;
    }
    
    /**
     * 描述匹配评分（不含偏好）
     */
    private int scoreDescriptionMatch(String description, List<String> keywords) {
        if (description == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        String lowerDesc = description.toLowerCase();
        int score = 0;
        
        for (String keyword : keywords) {
            if (lowerDesc.contains(keyword.toLowerCase())) {
                score += 1;
            }
        }
        
        return score;
    }
    
    /**
     * 判断是否为单一艺人
     */
    private boolean isSingleArtist(VideoInfo video) {
        if (video.getTitle() == null) {
            return true;
        }
        
        String title = video.getTitle().toLowerCase();
        
        // 如果包含合作关键词，则不是单一艺人
        for (String keyword : COLLABORATION_KEYWORDS) {
            if (title.contains(keyword.toLowerCase())) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 判断是否为合作视频
     */
    private boolean isCollaboration(VideoInfo video) {
        return !isSingleArtist(video);
    }
    
    /**
     * 判断是否为合集/串烧
     */
    private boolean isCollection(VideoInfo video) {
        if (video.getTitle() == null) {
            return false;
        }
        
        String title = video.getTitle().toLowerCase();
        
        for (String keyword : COLLECTION_KEYWORDS) {
            if (title.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 时长评分
     */
    private int scoreDuration(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) {
            return 0;
        }
        
        try {
            long durationMs = parseDuration(durationStr);
            
            if (durationMs > DURATION_TOO_LONG_MS) {
                return -2; // 超过10分钟
            } else if (durationMs < DURATION_TOO_SHORT_MS) {
                return -1; // 少于30秒
            }
            
        } catch (Exception e) {
            log.warn("无法解析时长: {}", durationStr);
        }
        
        return 0;
    }
    
    /**
     * 解析时长字符串为毫秒
     * 支持格式：mm:ss 或 hh:mm:ss
     */
    private long parseDuration(String duration) {
        String[] parts = duration.split(":");
        
        if (parts.length == 2) {
            // mm:ss
            int minutes = Integer.parseInt(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            return (minutes * 60 + seconds) * 1000L;
        } else if (parts.length == 3) {
            // hh:mm:ss
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);
            return (hours * 3600 + minutes * 60 + seconds) * 1000L;
        }
        
        return 0;
    }
    
    /**
     * 可信度评分（基于播放量和评论数）
     */
    private int scoreCredibility(VideoInfo video) {
        int score = 0;
        
        // 播放量加分
        if (video.getPlayCount() != null) {
            long plays = video.getPlayCount();
            if (plays > 1_000_000) {
                score += 3;
            } else if (plays > 100_000) {
                score += 2;
            } else if (plays > 10_000) {
                score += 1;
            }
        }
        
        // 评论数加分
        if (video.getCommentCount() != null) {
            long comments = video.getCommentCount();
            if (comments > 1_000) {
                score += 2;
            } else if (comments > 100) {
                score += 1;
            }
        }
        
        return score;
    }
    
    /**
     * 评分结果
     */
    @Data
    public static class ScoringResult {
        private VideoInfo video;
        private int score;
        private String reason;
        private boolean reject; // 是否直接拒绝
        
        /**
         * 接受阈值，支持 strict 等模式调整（默认 0）
         */
        private int acceptThreshold = 0;
        
        /**
         * 评分特征向量，用于调试/回放/离线分析
         */
        private ScoringFeatures features;
                
        public boolean isAccepted() {
            return !reject && score >= acceptThreshold;
        }
    }

    /**
     * 评分特征拆解
     */
    @Data
    public static class ScoringFeatures {
        // 基础匹配分
        private int titleScore;
        private int authorScore;
        private int tagScore;
        private int descriptionScore;

        // 结构特征
        private int singleArtistBonus;      // 单一艺人加分（2 或 0）
        private int collaborationAdjust;    // 合作视频调整（-3 / +2 / 0）
        private int collectionPenalty;      // 合集/串烧惩罚（-3 / 0）

        // 时长 & 热度
        private int durationPenalty;        // 时长惩罚（-2/-1/0）
        private int credibilityScore;       // 播放量 + 评论数加分

        // 探索/冷启动
        private int explorationBonus;       // 探索加成（取整）

        // 其他辅助信息
        private boolean negativeKeywordHit; // 是否命中负关键词
    }
}
