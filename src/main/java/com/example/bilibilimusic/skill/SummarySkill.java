package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.LlmBudgetService;
import com.example.bilibilimusic.service.OllamaService;
import com.example.bilibilimusic.service.PromptVersionService;
import com.example.bilibilimusic.service.PtqPromptService;
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
    private final LlmBudgetService llmBudgetService;
    private final OllamaService ollamaService;
    private final PromptVersionService promptVersionService;
    private final PtqPromptService ptqPromptService;
    
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

            LlmBudgetService.BudgetStatus budgetStatus = LlmBudgetService.BudgetStatus.AVAILABLE;
            if (!lowCost) {
                budgetStatus = llmBudgetService.checkAndConsume(context.getConversationId(), context.getUserId());
                if (budgetStatus != LlmBudgetService.BudgetStatus.AVAILABLE) {
                    log.info("[SummarySkill] LLM 预算{}，降级为规则总结: conversationId={}, userId={}, status={}",
                        budgetStatus == LlmBudgetService.BudgetStatus.EXCEEDED ? "已耗尽" : "接近耗尽",
                        context.getConversationId(), context.getUserId(), budgetStatus);
                }
            }
                
            List<VideoInfo> videos = context.getSelectedVideos();
            if (videos.isEmpty()) {
                context.setSummary("暂无视频可总结");
                context.setCurrentStage(PlaylistContext.Stage.COMPLETED);
                return false;
            }
                
            String summary;
            boolean useLlm = !lowCost && budgetStatus == LlmBudgetService.BudgetStatus.AVAILABLE;
            if (!useLlm) {
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
            String prompt = ptqPromptService.buildSummaryPrompt(videos, intent, selectionReason);
            String systemPrompt = promptVersionService.getPromptTemplate("generate_summary");
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = getSummarySystemPrompt();
            }
            String content = ollamaService.chat("generate_summary", systemPrompt, prompt, true, 15_000L);
            if (content != null && !content.isBlank()) {
                return content;
            }
        } catch (Exception e) {
            log.error("[SummarySkill] LLM 调用失败", e);
        }
        
        return buildFallbackSummary(videos, intent);
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
