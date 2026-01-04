package com.example.bilibilimusic.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class PlaylistResponse {

    /**
     * 通过 Playwright 从 B 站抓取的原始视频列表（最终采纳的视频）。
     */
    private List<VideoInfo> videos;

    /**
     * 调用本地 Ollama (qwen:7b) 后生成的歌单总结 / 推荐说明。
     */
    private String summary;

    /**
     * 垃圾桶候选（相关度较低或不确定的视频），用于作为“相关推荐”展示。
     */
    private List<VideoInfo> trashVideos;

    /**
     * 如果未来实现 MP3 下载，可以返回本地文件路径列表等信息（目前预留）。
     */
    private List<String> mp3Files;

    /**
     * 本次结果的可信度（0.0-1.0），基于命中率/接受率等指标估算。
     */
    private Double confidence;
    
    /**
     * 推荐结果解释（结构化），用于向用户说明为什么推荐这些视频。
     * 包含：匹配因素、偏好加成、探索策略、冷启动状态等。
     */
    private RecommendationExplanation explanation;
    
    /**
     * 推荐结果解释（结构化输出）
     */
    @Data
    @Builder
    public static class RecommendationExplanation {
        /**
         * 是否处于冷启动阶段
         */
        private Boolean coldStart;
        
        /**
         * 探索率（0.0-1.0）
         */
        private Double explorationRate;
        
        /**
         * 主要匹配因素（关键词、艺人等）
         */
        private List<String> matchFactors;
        
        /**
         * 偏好加成详情（艺人/关键词维度的权重贡献）
         */
        private Map<String, Integer> preferenceBonus;
        
        /**
         * 用户偏好置信度（归一化后的总体置信度）
         */
        private Double preferenceConfidence;
        
        /**
         * 推荐原因摘要
         */
        private String summary;
    }
}
