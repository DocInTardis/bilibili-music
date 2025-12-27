package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeywordExtractionSkill implements Skill {
    
    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${ollama.model}")
    private String model;
    
    @Override
    public boolean execute(PlaylistContext context) {
        try {
            String originalQuery = context.getIntent().getQuery();
            log.info("[KeywordExtractionSkill] 原始查询: {}", originalQuery);
            
            String extractedKeyword = extractKeyword(originalQuery);
            
            if (extractedKeyword != null && !extractedKeyword.isEmpty()) {
                context.getIntent().setQuery(extractedKeyword);
                log.info("[KeywordExtractionSkill] 提取的搜索关键词: {}", extractedKeyword);
                return true;
            } else {
                log.warn("[KeywordExtractionSkill] 关键词提取失败,使用原始查询");
                return true;
            }
            
        } catch (Exception e) {
            log.error("[KeywordExtractionSkill] 关键词提取失败", e);
            return true;
        }
    }
    
    private String extractKeyword(String query) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("stream", false);
            
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", getKeywordExtractionPrompt());
            
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", query);
            
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
                
                log.debug("[KeywordExtractionSkill] LLM 原始输出: {}", content);
                
                KeywordResult result = parseKeywordResult(content);
                if (result != null && result.getKeyword() != null) {
                    log.info("[KeywordExtractionSkill] LLM 理解: {}", result.getReason());
                    return result.getKeyword();
                }
                
                return content.trim().replaceAll("[\"'{}\\[\\]]", "");
            }
            
        } catch (Exception e) {
            log.error("[KeywordExtractionSkill] LLM 调用失败", e);
        }
        
        return query;
    }
    
    private String getKeywordExtractionPrompt() {
        return "你是一个关键词提取助手。\n" +
               "用户会输入一段自然语言,你需要提取其中的核心搜索关键词。\n" +
               "例如:\n" +
               "- 输入:帮我找夜鹿的歌 -> 输出:夜鹿\n" +
               "- 输入:来一份适合学习的纯音乐 -> 输出:纯音乐 学习\n" +
               "- 输入:搜索周杰伦的歌曲 -> 输出:周杰伦\n" +
               "\n" +
               "输出格式必须是 JSON:\n" +
               "{\n" +
               "  \"keyword\": \"提取的关键词\",\n" +
               "  \"reason\": \"提取理由\"\n" +
               "}\n" +
               "\n" +
               "如果无法确定,直接返回原文。\n" +
               "不要包含帮我找、搜索、来一份等辅助词。";
    }
    
    private KeywordResult parseKeywordResult(String content) {
        try {
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start >= 0 && end > start) {
                String json = content.substring(start, end + 1);
                return objectMapper.readValue(json, KeywordResult.class);
            }
        } catch (Exception e) {
            log.debug("[KeywordExtractionSkill] JSON 解析失败: {}", content);
        }
        return null;
    }
    
    @Override
    public String getName() {
        return "KeywordExtractionSkill";
    }
    
    @Data
    private static class KeywordResult {
        @JsonProperty("keyword")
        private String keyword;
        
        @JsonProperty("reason")
        private String reason;
    }
}
