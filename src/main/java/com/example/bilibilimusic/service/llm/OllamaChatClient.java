package com.example.bilibilimusic.service.llm;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaChatClient {

    private final WebClient ollamaWebClient;

    @CircuitBreaker(name = "ollama", fallbackMethod = "chatFallback")
    @Retry(name = "ollama")
    @RateLimiter(name = "ollama")
    @Bulkhead(name = "ollama")
    public String chat(String nodeName,
                       String promptVersion,
                       String model,
                       String systemPrompt,
                       String userPrompt,
                       long timeoutMs) {
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
            Object c = message != null ? message.get("content") : null;
            String content = c instanceof String ? (String) c : null;
            if (content != null && !content.isBlank()) {
                return content;
            }
        }

        throw new IllegalStateException("empty ollama response");
    }

    @SuppressWarnings("unused")
    private String chatFallback(String nodeName,
                                String promptVersion,
                                String model,
                                String systemPrompt,
                                String userPrompt,
                                long timeoutMs,
                                Throwable t) {
        log.warn("[OllamaChat] fallback: node={}, version={}, model={}, error={}",
            nodeName, promptVersion, model, t != null ? t.getMessage() : "null");
        return null;
    }
}

