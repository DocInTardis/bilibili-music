package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.VideoInfo;
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

    @Value("${ollama.model}")
    private String model;

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
