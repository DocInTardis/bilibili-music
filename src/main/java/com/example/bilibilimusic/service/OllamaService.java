package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.PromptVersionService;
import com.example.bilibilimusic.service.telemetry.LlmTelemetryService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaService {

    private final WebClient ollamaWebClient;
    private final PromptVersionService promptVersionService;
    private final CacheService cacheService;
    private final LlmTelemetryService llmTelemetryService;

    @Value("${ollama.model}")
    private String model;

    /**
     * 通用的 LLM Chat 调用入口。
     *
     * @param nodeName    调用节点名称（用于版本与缓存）
     * @param systemPrompt 系统 Prompt（风格/约束说明）
     * @param userPrompt   用户 Prompt（具体任务描述）
     * @param enableCache  是否启用结果缓存
     * @param timeoutMs    超时时间（毫秒）
     * @return LLM 返回的内容，失败或超时时返回 null
     */
    public String chat(String nodeName,
                       String systemPrompt,
                       String userPrompt,
                       boolean enableCache,
                       long timeoutMs) {
        String version = promptVersionService.getCurrentVersion(nodeName);
        String cacheKey = null;
        if (enableCache) {
            cacheKey = cacheService.buildPromptCacheKey(nodeName, version, userPrompt);
            String cached = cacheService.getCachedPromptResult(cacheKey);
            if (cached != null) {
                log.debug("[OllamaService] 命中 Prompt 结果缓存: node={}, version={}", nodeName, version);
                llmTelemetryService.recordChatCall(
                    nodeName,
                    version,
                    model,
                    true,
                    true,
                    0L,
                    estimateTokens(systemPrompt) + estimateTokens(userPrompt),
                    estimateTokens(cached)
                );
                return cached;
            }
        }

        long start = System.currentTimeMillis();
        String content = null;
        boolean success = false;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("stream", false);

            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);

            payload.put("messages", List.of(systemMessage, userMessage));

            Map<String, Object> response = ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .block(java.time.Duration.ofMillis(timeoutMs));

            if (response != null && response.containsKey("message")) {
                Map<String, Object> message = (Map<String, Object>) response.get("message");
                content = (String) message.get("content");
            }
            success = content != null && !content.isBlank();
        } catch (Exception e) {
            log.error("[OllamaService] 调用 LLM 失败: node={}", nodeName, e);
        } finally {
            long duration = System.currentTimeMillis() - start;
            // 简单估算 Token 与成本（以字符数 / 4 近似 Token 数）
            int promptTokens = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
            int completionTokens = estimateTokens(content);
            int totalTokens = promptTokens + completionTokens;
            double costPerThousand = 0.0; // 如需精确成本，可通过配置注入
            double estimatedCost = totalTokens / 1000.0 * costPerThousand;
            log.info("[LLM] node={} version={} tokens(p/c/t)={}/{}/{} duration={}ms cost≈{}",
                nodeName, version, promptTokens, completionTokens, totalTokens, duration, estimatedCost);

            llmTelemetryService.recordChatCall(
                nodeName,
                version,
                model,
                false,
                success,
                duration,
                promptTokens,
                completionTokens
            );
        }

        if (enableCache && cacheKey != null && content != null && !content.isBlank()) {
            cacheService.cachePromptResult(cacheKey, content);
        }

        return content;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // 粗略估算：4 个字符约等于 1 个 token
        return Math.max(1, text.length() / 4);
    }

    public Mono<String> summarizePlaylist(List<VideoInfo> videos, String userQuery, String preference) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户需求: ").append(userQuery).append("\n");
        if (preference != null && !preference.isBlank()) {
            builder.append("偏好: ").append(preference).append("\n");
        }
        builder.append("下面是从 B 站搜索到的视频列表，请帮我基于这些视频生成一份中文歌单推荐，总结风格与适合的场景，并列出推荐顺序（可以适当筛选，不必全部使用）：\n");

        int index = 1;
        for (VideoInfo v : videos) {
            builder.append(index++).append(". 标题: ").append(v.getTitle())
                    .append(" | 作者: ").append(v.getAuthor())
                    .append(" | 时长: ").append(v.getDuration())
                    .append(" | 链接: ").append(v.getUrl())
                    .append("\n");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("stream", false);

        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", "你是一个音乐推荐助手，善于根据 B 站视频生成歌单，回答使用简体中文。");

        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", builder.toString());

        payload.put("messages", List.of(systemMessage, userMessage));

        return ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(OllamaChatResponse.class)
                .map(resp -> {
                    if (resp.getMessage() == null) {
                        return "模型未返回内容";
                    }
                    return resp.getMessage().getContent();
                })
                .doOnError(e -> log.error("调用 Ollama 出错", e));
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OllamaChatResponse {
        private Message message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
    }
}
