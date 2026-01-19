package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.LlmBudgetService;
import com.example.bilibilimusic.service.OllamaService;
import com.example.bilibilimusic.service.PromptVersionService;
import com.example.bilibilimusic.service.PtqPromptService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final OllamaService ollamaService;
    private final PromptVersionService promptVersionService;
    private final PtqPromptService ptqPromptService;
    
    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.judge-models:}")
    private String judgeModels;

    @Value("${ollama.judge-quorum:0}")
    private int judgeQuorum;

    @Value("${ollama.judge-timeout-ms:8000}")
    private long judgeTimeoutMs;
    
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
            
            // 2. 【并行阶段】对所有视频并行计算基础评分（不依赖共享状态）
            long startTime = System.currentTimeMillis();
            List<VideoRelevanceScorer.ScoringResult> scoringResults = deduplicatedVideos.parallelStream()
                .map(video -> relevanceScorer.scoreVideo(video, context.getIntent()))
                .collect(Collectors.toList());
            long scoringTime = System.currentTimeMillis() - startTime;
            log.info("[CurationSkill] 并行评分完成，耗时: {} ms", scoringTime);
            
            // 3. 【串行阶段】按顺序进行相似度惩罚计算和视频选择（避免并发写 selectedVideos）
            List<VideoInfo> selectedVideos = new ArrayList<>();
            
            for (int i = 0; i < deduplicatedVideos.size(); i++) {
                VideoInfo video = deduplicatedVideos.get(i);
                VideoRelevanceScorer.ScoringResult result = scoringResults.get(i);
                
                // 检查与已选择视频的相似度，进行惩罚
                int similarityPenalty = duplicateFilter.getSimilarityPenalty(video, selectedVideos);
                result.setScore(result.getScore() + similarityPenalty);
                
                if (similarityPenalty < 0) {
                    result.setReason(result.getReason() + "; 相似度惩罚: " + similarityPenalty);
                }
                
                log.debug("[CurationSkill] 视频: {} | 分数: {} | 理由: {}", 
                    video.getTitle(), result.getScore(), result.getReason());
                
                // 4. 基于分数决策
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
                log.info("[CurationSkill] ???????? LLM ?????????????: {}", video.getTitle());
                return false;
            }
            String prompt = ptqPromptService.buildJudgementPrompt(video, intent);

            String systemPrompt = promptVersionService.getPromptTemplate("relevance_decision");
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = getJudgementSystemPrompt();
            }

            List<String> models = resolveJudgeModels();
            if (models.isEmpty()) {
                models = List.of(model);
            }
            if (models.size() == 1) {
                return judgeWithSingleModel(models.get(0), systemPrompt, prompt);
            }
            return judgeWithMultipleModels(models, systemPrompt, prompt);
        } catch (Exception e) {
            log.error("[CurationSkill] LLM????", e);
        }

        // LLM????????
        return false;
    }

    private boolean judgeWithSingleModel(String modelName, String systemPrompt, String prompt) {
        ModelVote vote = judgeWithModelVote(modelName, systemPrompt, prompt);
        return vote.accept != null && vote.accept;
    }

    private boolean judgeWithMultipleModels(List<String> models, String systemPrompt, String prompt) {
        int poolSize = Math.min(models.size(), 3);
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<CompletableFuture<ModelVote>> futures = new ArrayList<>();
            for (String m : models) {
                futures.add(CompletableFuture.supplyAsync(() -> judgeWithModelVote(m, systemPrompt, prompt), executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int accept = 0;
            int reject = 0;
            int unknown = 0;
            for (CompletableFuture<ModelVote> f : futures) {
                ModelVote vote = f.join();
                if (vote.accept == null) {
                    unknown++;
                } else if (vote.accept) {
                    accept++;
                } else {
                    reject++;
                }
            }

            int quorum = judgeQuorum > 0 ? judgeQuorum : (models.size() / 2 + 1);
            boolean decision = accept >= quorum;
            log.info("[CurationSkill] ?????: accept={}, reject={}, unknown={}, quorum={}, models={}",
                accept, reject, unknown, quorum, models);
            return decision;
        } finally {
            executor.shutdownNow();
        }
    }

    private ModelVote judgeWithModelVote(String modelName, String systemPrompt, String prompt) {
        String content = ollamaService.chatWithModel("relevance_decision", systemPrompt, prompt, false, judgeTimeoutMs, modelName);
        Boolean decision = parseDecision(content);
        if (decision == null) {
            log.debug("[CurationSkill] LLM???????: model={}, content={}", modelName, content);
        }
        return new ModelVote(modelName, decision, content);
    }

    private Boolean parseDecision(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String lower = content.toLowerCase();
        if (lower.contains("accept") || lower.contains("true") || lower.contains("??")) {
            return Boolean.TRUE;
        }
        if (lower.contains("reject") || lower.contains("false") || lower.contains("??")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private List<String> resolveJudgeModels() {
        if (judgeModels == null || judgeModels.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(judgeModels.split("[,;|]"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .collect(Collectors.toList());
    }

    private static class ModelVote {
        private final String model;
        private final Boolean accept;
        private final String raw;

        private ModelVote(String model, Boolean accept, String raw) {
            this.model = model;
            this.accept = accept;
            this.raw = raw;
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
