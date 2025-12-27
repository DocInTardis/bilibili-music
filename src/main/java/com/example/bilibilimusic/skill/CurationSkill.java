package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${ollama.model}")
    private String model;
    
    @Override
    public boolean execute(PlaylistContext context) {
        try {
            log.info("[CurationSkill] 开始筛选视频");
            context.setCurrentStage(PlaylistContext.Stage.CURATING);
            
            List<VideoInfo> videos = context.getSearchResults();
            if (videos.isEmpty()) {
                return false;
            }
            
            // 如果视频数量不多，直接全部保留
            if (videos.size() <= 5) {
                context.setSelectedVideos(videos);
                context.setSelectionReason("视频数量适中，全部保留");
                context.setCurrentStage(PlaylistContext.Stage.CURATED);
                return true;
            }
            
            // 调用 LLM 进行筛选
            CurationResult result = curateWithLLM(videos, context.getIntent());
            
            if (result != null && result.getSelectedIndices() != null) {
                List<VideoInfo> selected = result.getSelectedIndices().stream()
                    .filter(i -> i >= 0 && i < videos.size())
                    .map(videos::get)
                    .collect(Collectors.toList());
                
                context.setSelectedVideos(selected);
                context.setSelectionReason(result.getReason());
                context.setCurrentStage(PlaylistContext.Stage.CURATED);
                
                log.info("[CurationSkill] 筛选完成，从 {} 个视频中选出 {}", videos.size(), selected.size());
                return true;
            } else {
                // LLM 调用失败，使用简单策略
                log.warn("[CurationSkill] LLM 筛选失败，使用简单策略");
                context.setSelectedVideos(videos.subList(0, Math.min(10, videos.size())));
                context.setSelectionReason("LLM 不可用，保留前 10 个结果");
                context.setCurrentStage(PlaylistContext.Stage.CURATED);
                return true;
            }
            
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
     * 使用 LLM 进行视频筛选
     */
    private CurationResult curateWithLLM(List<VideoInfo> videos, com.example.bilibilimusic.context.UserIntent intent) {
        try {
            String prompt = buildCurationPrompt(videos, intent);
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("stream", false);
            
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", getCurationSystemPrompt());
            
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
                return parseCurationResult(content);
            }
            
        } catch (Exception e) {
            log.error("[CurationSkill] LLM 调用失败", e);
        }
        return null;
    }
    
    /**
     * 构建筛选 Prompt
     */
    private String buildCurationPrompt(List<VideoInfo> videos, com.example.bilibilimusic.context.UserIntent intent) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求：").append(intent.getQuery()).append("\n");
        if (intent.getPreference() != null && !intent.getPreference().isBlank()) {
            sb.append("用户偏好：").append(intent.getPreference()).append("\n");
        }
        sb.append("\n搜索到的视频列表：\n");
        
        for (int i = 0; i < videos.size(); i++) {
            VideoInfo v = videos.get(i);
            sb.append(String.format("[%d] 标题: %s | 作者: %s | 时长: %s", 
                i, v.getTitle(), v.getAuthor(), v.getDuration()));
            
            // 添加标签信息（如果有）
            if (v.getTags() != null && !v.getTags().isBlank()) {
                sb.append(" | 标签: ").append(v.getTags());
            }
            sb.append("\n");
        }
        
        sb.append("\n请从以上视频中筛选出最符合用户需求的 5-10 个视频。\n");
        sb.append("筛选时请重点关注：\n");
        sb.append("1. 视频标题是否包含用户搜索的关键词\n");
        sb.append("2. 标签是否与用户需求相关（如果有标签）\n");
        sb.append("3. 作者是否是用户要找的人\n");
        sb.append("4. 过滤掉明显不相关的视频（比如其他人的翻唱、其他类型的视频）\n");
        sb.append("\n返回格式必须是 JSON：\n");
        sb.append("{\n");
        sb.append("  \"selectedIndices\": [0, 2, 5],\n");
        sb.append("  \"reason\": \"筛选理由\"\n");
        sb.append("}\n");
        
        return sb.toString();
    }
    
    /**
     * PTQ 系统 Prompt
     */
    private String getCurationSystemPrompt() {
        return "你是 CurationSkill 的执行器。\n" +
               "你只能基于输入视频信息进行筛选与排序。\n" +
               "不允许引入任何外部知识。\n" +
               "输出必须是严格的 JSON 格式，包含 selectedIndices 数组和 reason 字符串。\n" +
               "selectedIndices 是视频索引数组（从0开始）。";
    }
    
    /**
     * 解析筛选结果
     */
    private CurationResult parseCurationResult(String content) {
        try {
            // 尝试提取 JSON 部分
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start >= 0 && end > start) {
                String json = content.substring(start, end + 1);
                return objectMapper.readValue(json, CurationResult.class);
            }
        } catch (Exception e) {
            log.error("[CurationSkill] 解析 LLM 输出失败: {}", content, e);
        }
        return null;
    }
    
    @Override
    public String getName() {
        return "CurationSkill";
    }
    
    /**
     * 筛选结果
     */
    @Data
    private static class CurationResult {
        @JsonProperty("selectedIndices")
        private List<Integer> selectedIndices;
        
        @JsonProperty("reason")
        private String reason;
    }
}
