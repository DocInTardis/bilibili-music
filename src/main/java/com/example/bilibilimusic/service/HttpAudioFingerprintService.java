package com.example.bilibilimusic.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 可插拔的音频指纹识别实现：
 * - 通过外部 HTTP API 识别视频音轨，并返回歌曲数量等信息
 *
 * 说明：不同供应商能力差异较大（可能需要音频片段而非 URL），因此这里实现为“把 videoUrl 交给外部服务处理”的通用适配层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audio.fingerprint.enabled", havingValue = "true")
public class HttpAudioFingerprintService implements AudioFingerprintService {

    private final WebClient.Builder webClientBuilder;

    @Value("${audio.fingerprint.base-url}")
    private String baseUrl;

    @Value("${audio.fingerprint.api-key:}")
    private String apiKey;

    @Value("${audio.fingerprint.timeout-ms:4000}")
    private long timeoutMs;

    @Override
    public Integer estimateTrackCount(String videoUrl) {
        if (videoUrl == null || videoUrl.isBlank()) {
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }

        try {
            WebClient client = webClientBuilder.baseUrl(baseUrl).build();
            Request payload = new Request();
            payload.setVideoUrl(videoUrl);
            payload.setPlatform("bilibili");

            WebClient.RequestBodySpec req = client.post()
                .uri("/estimate")
                .contentType(MediaType.APPLICATION_JSON);

            if (apiKey != null && !apiKey.isBlank()) {
                req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }

            Response resp = req
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Response.class)
                .block(java.time.Duration.ofMillis(timeoutMs));

            if (resp == null) {
                return null;
            }
            Integer trackCount = resp.getTrackCount();
            if (trackCount == null || trackCount <= 0) {
                return null;
            }
            return trackCount;
        } catch (Exception e) {
            log.debug("[AudioFP] estimateTrackCount failed: {}", e.getMessage());
            return null;
        }
    }

    @Data
    private static class Request {
        private String videoUrl;
        private String platform;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Response {
        @JsonProperty("trackCount")
        private Integer trackCount;
    }
}

