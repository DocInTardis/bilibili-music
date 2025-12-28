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
}
