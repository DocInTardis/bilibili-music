package com.example.bilibilimusic.service.embedding;

import com.example.bilibilimusic.util.HashUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Ollama 的 embedding 适配层（可选能力）。
 *
 * - 默认使用 Redis 做 embedding 缓存，避免重复向量化。
 * - 不依赖向量数据库模块；语义相似度由本地 cosine 计算。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaEmbeddingService implements EmbeddingService {

    private static final long TTL_DAYS = 7;

    private final WebClient ollamaWebClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ollama.embeddings-enabled:false}")
    private boolean enabled;

    @Value("${ollama.embedding-model:nomic-embed-text}")
    private String embeddingModel;

    @Value("${ollama.embedding-timeout-ms:4000}")
    private long timeoutMs;

    @Override
    public float[] embed(String text) {
        if (!enabled) {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }

        String key = buildCacheKey(embeddingModel, text);
        float[] cached = getCached(key);
        if (cached != null) {
            return cached;
        }

        float[] vector = callOllamaEmbeddings(text);
        if (vector != null) {
            cache(key, vector);
        }
        return vector;
    }

    private float[] callOllamaEmbeddings(String text) {
        // 优先尝试 /api/embeddings（兼容旧版本）
        try {
            EmbeddingsRequest request = new EmbeddingsRequest();
            request.setModel(embeddingModel);
            request.setPrompt(text);
            EmbeddingsResponse response = ollamaWebClient.post()
                .uri("/api/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingsResponse.class)
                .block(java.time.Duration.ofMillis(timeoutMs));
            if (response != null && response.getEmbedding() != null && !response.getEmbedding().isEmpty()) {
                return toFloatArray(response.getEmbedding());
            }
        } catch (Exception e) {
            log.debug("[Embedding] /api/embeddings failed: {}", e.getMessage());
        }

        // 回退尝试 /api/embed（兼容新版本）
        try {
            EmbedRequest request = new EmbedRequest();
            request.setModel(embeddingModel);
            request.setInput(text);
            EmbedResponse response = ollamaWebClient.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .block(java.time.Duration.ofMillis(timeoutMs));
            if (response != null && response.getEmbeddings() != null && !response.getEmbeddings().isEmpty()) {
                List<Double> emb = response.getEmbeddings().get(0);
                if (emb != null && !emb.isEmpty()) {
                    return toFloatArray(emb);
                }
            }
        } catch (Exception e) {
            log.debug("[Embedding] /api/embed failed: {}", e.getMessage());
        }

        return null;
    }

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Double v = list.get(i);
            arr[i] = v != null ? v.floatValue() : 0.0f;
        }
        return arr;
    }

    private String buildCacheKey(String model, String input) {
        String m = model != null ? model : "unknown";
        return "emb:" + m + ":" + HashUtil.md5(input);
    }

    private void cache(String key, float[] vector) {
        try {
            String encoded = encode(vector);
            stringRedisTemplate.opsForValue().set(key, encoded, TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.debug("[Embedding] cache failed: {}", e.getMessage());
        }
    }

    private float[] getCached(String key) {
        try {
            String encoded = stringRedisTemplate.opsForValue().get(key);
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            return decode(encoded);
        } catch (Exception e) {
            return null;
        }
    }

    private String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(vector.length);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private float[] decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int len = buffer.getInt();
        if (len <= 0 || len > 8192) {
            return null;
        }
        float[] vector = new float[len];
        for (int i = 0; i < len; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    @Data
    private static class EmbeddingsRequest {
        private String model;
        private String prompt;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingsResponse {
        private List<Double> embedding;
    }

    @Data
    private static class EmbedRequest {
        private String model;
        private String input;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbedResponse {
        @JsonProperty("embeddings")
        private List<List<Double>> embeddings;
    }
}

