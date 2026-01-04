package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌单总结生成能力
 * 
 * 职责：
 * - 对已筛选的视频列表生成文字说明
 * 
 * 📌 LLM 只负责"表达"，不再负责筛选与决策
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SummarySkill implements Skill {
    
    private final WebClient ollamaWebClient;
    
    @Value("${ollama.model}")
    private String model;
    
    @Override
    public boolean execute(PlaylistContext context) {
        try {
            String mode = context.getIntent() != null ? context.getIntent().getMode() : null;
            java.util.Set<String> modeTags = parseModeTags(mode);
            boolean lowCost = modeTags.contains("low_cost");
            log.info("[SummarySkill] 开始生成歌单总结 (mode={}, tags={})", mode, modeTags);
            context.setCurrentStage(PlaylistContext.Stage.SUMMARIZING);
                
            List<VideoInfo> videos = context.getSelectedVideos();
            if (videos.isEmpty()) {
                context.setSummary("暂无视频可总结");
                context.setCurrentStage(PlaylistContext.Stage.COMPLETED);
                return false;
            }
                
            String summary;
            if (lowCost) {
                // 低成本模式：跳过 LLM，直接使用降级总结
                log.info("[SummarySkill] 低成本模式：跳过 LLM，总结使用降级方案");
                summary = buildFallbackSummary(videos, context.getIntent());
            } else {
                summary = generateSummary(videos, context.getIntent(), context.getSelectionReason());
            }
            context.setSummary(summary);
            context.setCurrentStage(PlaylistContext.Stage.COMPLETED);
                
            log.info("[SummarySkill] 总结生成完成");
            return true;
    
        } catch (Exception e) {
            log.error("[SummarySkill] 生成总结失败", e);
            context.setSummary("总结生成失败，但已完成视频筛选");
            context.setCurrentStage(PlaylistContext.Stage.COMPLETED);
            return false;
        }
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
     * 生成歌单总结
     */
    private String generateSummary(List<VideoInfo> videos, 
                                   com.example.bilibilimusic.context.UserIntent intent,
                                   String selectionReason) {
        try {
            String prompt = buildSummaryPrompt(videos, intent, selectionReason);
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("stream", false);
            
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", getSummarySystemPrompt());
            
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            
            payload.put("messages", List.of(systemMessage, userMessage));
            
            Map<String, Object> response = ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
            
            if (response != null && response.containsKey("message")) {
                Map<String, Object> message = (Map<String, Object>) response.get("message");
                return (String) message.get("content");
            }
            
        } catch (Exception e) {
            log.error("[SummarySkill] LLM 调用失败", e);
        }
        
        return buildFallbackSummary(videos, intent);
    }
    
    /**
     * 构建总结 Prompt
     */
    private String buildSummaryPrompt(List<VideoInfo> videos, 
                                     com.example.bilibilimusic.context.UserIntent intent,
                                     String selectionReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求：").append(intent.getQuery()).append("\n");
        if (intent.getPreference() != null && !intent.getPreference().isBlank()) {
            sb.append("用户偏好：").append(intent.getPreference()).append("\n");
        }
        if (selectionReason != null && !selectionReason.isBlank()) {
            sb.append("筛选理由：").append(selectionReason).append("\n");
        }
        sb.append("\n已筛选的视频列表：\n");
        
        for (int i = 0; i < videos.size(); i++) {
            VideoInfo v = videos.get(i);
            sb.append(String.format("%d. %s - %s（%s）\n", 
                i + 1, v.getTitle(), v.getAuthor(), v.getDuration()));
        }
        
        sb.append("\n请生成一段简洁的中文歌单推荐说明（100字以内），包括：\n");
        sb.append("1. 整体风格特点\n");
        sb.append("2. 适合的场景\n");
        sb.append("3. 为什么推荐这些视频\n");
        
        return sb.toString();
    }
    
    /**
     * PTQ 系统 Prompt
     */
    private String getSummarySystemPrompt() {
        return "你是一个音乐推荐助手，善于根据已筛选的 B 站视频生成歌单推荐说明。\n" +
               "你的回答必须：\n" +
               "1. 使用简体中文\n" +
               "2. 简洁明了，不超过 100 字\n" +
               "3. 只基于提供的视频信息，不引入外部知识\n" +
               "4. 直接输出推荐文案，不要额外的格式标记";
    }
    
    /**
     * 降级方案：简单总结
     */
    private String buildFallbackSummary(List<VideoInfo> videos, 
                                        com.example.bilibilimusic.context.UserIntent intent) {
        return String.format("为您找到 %d 首符合「%s」的视频，已按相关度排序，可直接播放。", 
            videos.size(), intent.getQuery());
    }
    
    @Override
    public String getName() {
        return "SummarySkill";
    }
}
