package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.dto.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 视频去重与相似度过滤器
 * 
 * 目标：
 * 1. 标题归一化（去除特殊符号、统一大小写）
 * 2. 检测已存在的视频，降低权重
 * 3. 检测高度相似的视频（避免重复推荐）
 */
@Component
@Slf4j
public class VideoDuplicateFilter {
    
    // 需要移除的特殊符号和空格
    private static final Pattern SPECIAL_CHARS = Pattern.compile("[\\s\\p{Punct}【】「」『』（）()\\[\\]｜|]+");
    
    // 相似度阈值
    private static final double SIMILARITY_THRESHOLD = 0.8;
    
    /**
     * 标题归一化
     * 
     * @param title 原始标题
     * @return 归一化后的标题
     */
    public String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        
        // 转小写
        String normalized = title.toLowerCase();
        
        // 移除特殊符号和空格
        normalized = SPECIAL_CHARS.matcher(normalized).replaceAll("");
        
        return normalized;
    }
    
    /**
     * 检查视频是否与已存在视频重复
     * 
     * @param video 待检查的视频
     * @param existingVideos 已存在的视频列表
     * @return 如果重复返回true
     */
    public boolean isDuplicate(VideoInfo video, List<VideoInfo> existingVideos) {
        if (existingVideos == null || existingVideos.isEmpty()) {
            return false;
        }
        
        String normalizedTitle = normalizeTitle(video.getTitle());
        
        for (VideoInfo existing : existingVideos) {
            // 1. URL完全相同
            if (video.getUrl() != null && video.getUrl().equals(existing.getUrl())) {
                log.debug("发现重复视频（URL相同）: {}", video.getTitle());
                return true;
            }
            
            // 2. 标题归一化后完全相同
            String existingNormalized = normalizeTitle(existing.getTitle());
            if (!normalizedTitle.isEmpty() && normalizedTitle.equals(existingNormalized)) {
                log.debug("发现重复视频（标题相同）: {} vs {}", video.getTitle(), existing.getTitle());
                return true;
            }
            
            // 3. 相似度检查
            double similarity = calculateSimilarity(normalizedTitle, existingNormalized);
            if (similarity >= SIMILARITY_THRESHOLD) {
                log.debug("发现高度相似视频（相似度: {:.2f}）: {} vs {}", 
                    similarity, video.getTitle(), existing.getTitle());
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 计算两个字符串的相似度（使用Jaccard相似度）
     * 
     * @param str1 字符串1
     * @param str2 字符串2
     * @return 相似度 [0, 1]
     */
    public double calculateSimilarity(String str1, String str2) {
        if (str1 == null || str2 == null || str1.isEmpty() || str2.isEmpty()) {
            return 0.0;
        }
        
        // 转换为字符集合
        Set<Character> set1 = new HashSet<>();
        Set<Character> set2 = new HashSet<>();
        
        for (char c : str1.toCharArray()) {
            set1.add(c);
        }
        
        for (char c : str2.toCharArray()) {
            set2.add(c);
        }
        
        // 计算交集
        Set<Character> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        // 计算并集
        Set<Character> union = new HashSet<>(set1);
        union.addAll(set2);
        
        if (union.isEmpty()) {
            return 0.0;
        }
        
        // Jaccard 相似度 = |交集| / |并集|
        return (double) intersection.size() / union.size();
    }
    
    /**
     * 从视频列表中过滤重复项
     * 
     * @param videos 视频列表
     * @return 去重后的视频列表
     */
    public List<VideoInfo> filterDuplicates(List<VideoInfo> videos) {
        if (videos == null || videos.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<VideoInfo> filtered = new ArrayList<>();
        
        for (VideoInfo video : videos) {
            if (!isDuplicate(video, filtered)) {
                filtered.add(video);
            }
        }
        
        log.info("去重完成：原始 {} 个，去重后 {} 个", videos.size(), filtered.size());
        
        return filtered;
    }
    
    /**
     * 检查视频是否与已选择的视频相似
     * 如果相似，返回相似度惩罚分数（负数）
     * 
     * @param video 待检查的视频
     * @param selectedVideos 已选择的视频列表
     * @return 惩罚分数（0表示不相似，负数表示相似度惩罚）
     */
    public int getSimilarityPenalty(VideoInfo video, List<VideoInfo> selectedVideos) {
        if (selectedVideos == null || selectedVideos.isEmpty()) {
            return 0;
        }
        
        String normalizedTitle = normalizeTitle(video.getTitle());
        
        for (VideoInfo selected : selectedVideos) {
            String selectedNormalized = normalizeTitle(selected.getTitle());
            double similarity = calculateSimilarity(normalizedTitle, selectedNormalized);
            
            if (similarity >= SIMILARITY_THRESHOLD) {
                // 相似度越高，惩罚越重
                return -(int) (similarity * 5); // 最多-5分
            }
        }
        
        return 0;
    }
}
