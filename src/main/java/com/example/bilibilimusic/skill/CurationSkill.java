package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.LlmBudgetService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 视频筛选与排序能力
 * ⚠️ 这是你当前系统中"缺失但最关键的 Agent 能力"
 * 
 * 职责：
 * - 从搜索结果中筛选、排序视频
 * - 决定哪些视频适合进入最终歌单
 * 
 * 📌 LLM 只负责"判断"，不再负责生成内容
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CurationSkill implements Skill {
    
    private final WebClient ollamaWebClient;
    private final VideoRelevanceScorer relevanceScorer;
    private final VideoDuplicateFilter duplicateFilter;
    private final LlmBudgetService llmBudgetService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${ollama.model}")
    private String model;
    
    // 评分阈值配置（可自适应调整）
    private int minScoreThreshold = 3;  // 最低接受分数（目前未直接使用，预留）
    private int llmThresholdLow = 2;    // 分数低于此值，直接拒绝
    private int llmThresholdHigh = 8;   // 分数高于此值，直接接受
    // 介于两者之间，调用LLM做最终判断
    
    @Override
    public boolean execute(PlaylistContext context) {
        try {
            log.info("[CurationSkill] 开始筛选视频（使用多维度评分系统）");
            context.setCurrentStage(PlaylistContext.Stage.CURATING);
            
            List<VideoInfo> videos = context.getSearchResults();
            if (videos.isEmpty()) {
                log.warn("[CurationSkill] 搜索结果为空");
                return false;
            }
            
            // 1. 先去重
            List<VideoInfo> deduplicatedVideos = duplicateFilter.filterDuplicates(videos);
            log.info("[CurationSkill] 去重后视频数量: {}", deduplicatedVideos.size());
            
            // 2. 使用评分系统评估每个视频
            List<VideoRelevanceScorer.ScoringResult> scoringResults = new ArrayList<>();
            List<VideoInfo> selectedVideos = new ArrayList<>();
            
            for (VideoInfo video : deduplicatedVideos) {
                // 计算相关性分数
                VideoRelevanceScorer.ScoringResult result = relevanceScorer.scoreVideo(video, context.getIntent());
                
                // 检查与已选择视频的相似度，进行惩罚
                int similarityPenalty = duplicateFilter.getSimilarityPenalty(video, selectedVideos);
                result.setScore(result.getScore() + similarityPenalty);
                
                if (similarityPenalty < 0) {
                    result.setReason(result.getReason() + "; 相似度惩罚: " + similarityPenalty);
                }
                
                scoringResults.add(result);
                
                log.debug("[CurationSkill] 视频: {} | 分数: {} | 理由: {}", 
                    video.getTitle(), result.getScore(), result.getReason());
                
                // 3. 基于分数决策
                if (result.isReject()) {
                    log.debug("[CurationSkill] 直接拒绝: {}", video.getTitle());
                    continue;
                }
                                
                if (result.getScore() >= llmThresholdHigh) {
                    // 高分直接接受
                    selectedVideos.add(video);
                    log.info("[CurationSkill] 高分直接接受 ({}): {}", result.getScore(), video.getTitle());
                } else if (result.getScore() <= llmThresholdLow) {
                    // 低分直接拒绝
                    log.debug("[CurationSkill] 低分直接拒绝 ({}): {}", result.getScore(), video.getTitle());
                } else {
                    // 边界情况，调用LLM做最终判断（受 low_cost 和配额控制）
                    log.info("[CurationSkill] 边界分数 ({})，准备调用LLM判断: {}", 
                        result.getScore(), video.getTitle());
                
                    LlmBudgetService.BudgetStatus budgetStatus = llmBudgetService.checkAndConsume(
                        context.getConversationId(), context.getUserId());
                    if (budgetStatus != LlmBudgetService.BudgetStatus.AVAILABLE) {
                        log.info("[CurationSkill] LLM 预算{}，跳过边界 LLM 判断，默认拒绝: conversationId={}, userId={}, status={}, video={}",
                            budgetStatus == LlmBudgetService.BudgetStatus.EXCEEDED ? "已耗尽" : "接近耗尽",
                            context.getConversationId(), context.getUserId(), budgetStatus, video.getTitle());
                        continue;
                    }
                                     
                    boolean llmAccept = judgeVideoWithLLM(video, context.getIntent());
                    if (llmAccept) {
                        selectedVideos.add(video);
                        log.info("[CurationSkill] LLM判断接受: {}", video.getTitle());
                    } else {
                        log.debug("[CurationSkill] LLM判断拒绝: {}", video.getTitle());
                    }
                }
            }
            
            // 4. 按分数排序
            selectedVideos.sort((v1, v2) -> {
                VideoRelevanceScorer.ScoringResult r1 = findScoringResult(scoringResults, v1);
                VideoRelevanceScorer.ScoringResult r2 = findScoringResult(scoringResults, v2);
                return Integer.compare(r2.getScore(), r1.getScore()); // 降序
            });
            
            context.setSelectedVideos(selectedVideos);
            context.setSelectionReason(String.format(
                "从 %d 个视频中筛选出 %d 个，基于多维度评分系统",
                videos.size(), selectedVideos.size()
            ));
            context.setCurrentStage(PlaylistContext.Stage.CURATED);
            
            log.info("[CurationSkill] 筛选完成，从 {} 个视频中选出 {}", 
                videos.size(), selectedVideos.size());
            return true;
            
        } catch (Exception e) {
            log.error("[CurationSkill] 筛选失败", e);
            // 失败时返回所有结果
            context.setSelectedVideos(context.getSearchResults());
            context.setSelectionReason("筛选失败，返回所有结果");
            context.setCurrentStage(PlaylistContext.Stage.CURATED);
            return true;
        }
    }
    
    /**
     * 查找视频的评分结果
     */
    private VideoRelevanceScorer.ScoringResult findScoringResult(
            List<VideoRelevanceScorer.ScoringResult> results, VideoInfo video) {
        for (VideoRelevanceScorer.ScoringResult result : results) {
            if (result.getVideo().equals(video)) {
                return result;
            }
        }
        // 默认返回0分
        VideoRelevanceScorer.ScoringResult defaultResult = new VideoRelevanceScorer.ScoringResult();
        defaultResult.setScore(0);
        return defaultResult;
    }
    
    /**
     * 使用LLM判断视频（仅用于边界情况）
     */
    public boolean judgeVideoWithLLM(VideoInfo video, com.example.bilibilimusic.context.UserIntent intent) {
        try {
            String mode = intent != null ? intent.getMode() : null;
            java.util.Set<String> modeTags = parseModeTags(mode);
            boolean lowCost = modeTags.contains("low_cost");
            if (lowCost) {
                log.info("[CurationSkill] 低成本模式：跳过 LLM 边界判断，默认拒绝边界视频: {}", video.getTitle());
                return false;
            }
            String prompt = buildJudgementPrompt(video, intent);
    
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("stream", false);
            
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", getJudgementSystemPrompt());
            
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
                String content = (String) message.get("content");
                
                // 解析结果：包含"accept" 或 "true"
                String lowerContent = content.toLowerCase();
                return lowerContent.contains("accept") || lowerContent.contains("true") || lowerContent.contains("接受");
            }
            
        } catch (Exception e) {
            log.error("[CurationSkill] LLM判断失败", e);
        }
        
        // LLM失败时，默认拒绝
        return false;
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
     * 构建判断 Prompt
     */
    private String buildJudgementPrompt(VideoInfo video, com.example.bilibilimusic.context.UserIntent intent) {
        return String.format(
            "用户需求：%s\n" +
            "关键词：%s\n" +
            "\n视频信息：\n" +
            "标题：%s\n" +
            "作者：%s\n" +
            "时长：%s\n" +
            "\n请判断这个视频是否符合用户需求。\n" +
            "只需回答 'accept' 或 'reject'。",
            intent.getQuery(),
            intent.getKeywords() != null ? String.join(", ", intent.getKeywords()) : "",
            video.getTitle(),
            video.getAuthor(),
            video.getDuration()
        );
    }
    
    /**
     * 判断 Prompt 系统设定
     */
    private String getJudgementSystemPrompt() {
        return "你是一个视频相关性判断器。\n" +
               "你的任务是判断视频是否符合用户需求。\n" +
               "只能回答 'accept' 或 'reject'，不要有其他内容。";
    }
    
    @Override
    public String getName() {
        return "CurationSkill";
    }
    
    // ==================== 阈值访问与自适应调整 ====================
    public int getMinScoreThreshold() {
        return minScoreThreshold;
    }
    
    public void setMinScoreThreshold(int minScoreThreshold) {
        this.minScoreThreshold = Math.max(0, minScoreThreshold);
    }
    
    public int getLlmThresholdLow() {
        return llmThresholdLow;
    }
    
    public int getLlmThresholdHigh() {
        return llmThresholdHigh;
    }
    
    public void setLlmThresholds(int low, int high) {
        // 保证 0 <= low < high <= 10 的基本约束
        int normalizedLow = Math.max(0, low);
        int normalizedHigh = Math.max(normalizedLow + 1, Math.min(10, high));
        log.info("[CurationSkill] 更新 LLM 阈值: low {} -> {}, high {} -> {}", 
            this.llmThresholdLow, normalizedLow, this.llmThresholdHigh, normalizedHigh);
        this.llmThresholdLow = normalizedLow;
        this.llmThresholdHigh = normalizedHigh;
    }
}
