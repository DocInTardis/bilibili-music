package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 视频相关性评分器
 * 
 * 核心思想：准确性 = （更好的候选）×（更准的相关性判断）×（更懂用户）×（更少误判）
 */
@Component
@Slf4j
public class VideoRelevanceScorer {
    
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
        ScoringResult result = new ScoringResult();
        result.setVideo(video);
        
        int totalScore = 0;
        List<String> reasons = new ArrayList<>();
        
        // 1. 负关键词过滤（优先级最高，直接拒绝）
        if (containsNegativeKeywords(video)) {
            result.setScore(-100);
            result.setReason("包含负关键词，直接拒绝");
            result.setReject(true);
            return result;
        }
        
        // 2. 标题命中关键词 (+5 per keyword)
        int titleScore = scoreTitleMatch(video.getTitle(), intent.getKeywords());
        totalScore += titleScore;
        if (titleScore > 0) {
            reasons.add(String.format("标题命中关键词: +%d", titleScore));
        }
        
        // 3. 作者命中关键词 (+4 per artist)
        int authorScore = scoreAuthorMatch(video.getAuthor(), intent);
        totalScore += authorScore;
        if (authorScore > 0) {
            reasons.add(String.format("作者匹配: +%d", authorScore));
        }
        
        // 4. 标签命中 (+3 per tag)
        int tagScore = scoreTagMatch(video.getTags(), intent.getKeywords());
        totalScore += tagScore;
        if (tagScore > 0) {
            reasons.add(String.format("标签匹配: +%d", tagScore));
        }
        
        // 5. 描述命中 (+1 per keyword)
        int descScore = scoreDescriptionMatch(video.getDescription(), intent.getKeywords());
        totalScore += descScore;
        if (descScore > 0) {
            reasons.add(String.format("描述匹配: +%d", descScore));
        }
        
        // 6. 单一艺人 (+2)
        if (isSingleArtist(video)) {
            totalScore += 2;
            reasons.add("单一艺人: +2");
        }
        
        // 7. 合作视频 (根据用户偏好决定)
        if (isCollaboration(video)) {
            if (intent.isSingleArtistOnly()) {
                totalScore -= 3;
                reasons.add("合作视频（用户要求单一艺人）: -3");
            } else {
                totalScore += 2;
                reasons.add("合作视频: +2");
            }
        }
        
        // 8. 合集/串烧 (-3)
        if (isCollection(video)) {
            totalScore -= 3;
            reasons.add("合集/串烧: -3");
        }
        
        // 9. 时长异常 (-2)
        int durationPenalty = scoreDuration(video.getDuration());
        if (durationPenalty < 0) {
            totalScore += durationPenalty;
            reasons.add(String.format("时长异常: %d", durationPenalty));
        }
        
        // 10. 可信度加分（播放量、评论数）
        int credibilityScore = scoreCredibility(video);
        totalScore += credibilityScore;
        if (credibilityScore > 0) {
            reasons.add(String.format("可信度: +%d", credibilityScore));
        }
        
        result.setScore(totalScore);
        result.setReason(String.join("; ", reasons));
        result.setReject(totalScore < 0); // 负分直接拒绝
        
        return result;
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
     * 标题匹配评分
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
     * 作者匹配评分
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
     * 标签匹配评分
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
     * 描述匹配评分
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
        
        public boolean isAccepted() {
            return !reject && score > 0;
        }
    }
}
