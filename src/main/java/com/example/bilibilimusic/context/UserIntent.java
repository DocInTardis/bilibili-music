package com.example.bilibilimusic.context;

import lombok.Builder;
import lombok.Data;

/**
 * 用户意图建模
 */
@Data
@Builder
public class UserIntent {
    
    /**
     * 原始用户输入（完整自然语言）
     */
    private String query;
    
    /**
     * 目标歌曲数量（以“首”为单位）
     */
    private int targetCount;
    
    /**
     * 期望视频数量（用于控制每轮抓取的视频上限，向下兼容）
     */
    private int limit;
    
    /**
     * 使用场景（如：学习 / 通勤 / 放松）
     */
    private String scenario;
    
    /**
     * 风格偏好（如：轻音乐、摇滚、古典）
     */
    private String preference;
    
    /**
     * 从意图与描述中提取出的搜索关键词列表
     */
    private java.util.List<String> keywords;
    
    /**
     * 是否下载为 MP3
     */
    private boolean downloadAsMp3;
    
    // ==================== 细化意图字段 ====================
    
    /**
     * 艺人列表（用户指定的歌手/UP主）
     */
    private java.util.List<String> artists;
    
    /**
     * 风格标签（摇滚、流行、古典、电子等）
     */
    private java.util.List<String> genres;
    
    /**
     * 情绪/氛围（放松、激昂、悲伤、快乐等）
     */
    private java.util.List<String> moods;
    
    /**
     * 语言偏好（中文、日语、英语、纯音乐等）
     */
    private String language;
    
    /**
     * 是否只要单一艺人作品（排除合作/混剪）
     */
    private boolean singleArtistOnly;
}
