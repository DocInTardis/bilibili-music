package com.example.bilibilimusic.service.websocket;

import com.example.bilibilimusic.dto.ChatMessage;
import com.example.bilibilimusic.service.PromptVersionService;
import com.example.bilibilimusic.service.observability.AgentObservabilityMetrics;
import com.example.bilibilimusic.service.telemetry.AgentTraceContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket Topic 发布器：
 * - 单机：直接 SimpMessagingTemplate.convertAndSend
 * - 多实例：通过 Redis Pub/Sub 广播，再由本机订阅回灌到 SimpMessagingTemplate
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisBackedWsTopicPublisher implements WsTopicPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final PromptVersionService promptVersionService;
    private final AgentObservabilityMetrics observabilityMetrics;

    @Value("${app.websocket.cluster.enabled:false}")
    private boolean clusterEnabled;

    @Value("${app.websocket.cluster.channel:ws:topic:broadcast}")
    private String channel;

    private final String instanceId = java.util.UUID.randomUUID().toString();

    @Override
    public void send(String destination, Object payload) {
        Object enriched = enrichTrace(destination, payload);
        if (!clusterEnabled) {
            messagingTemplate.convertAndSend(destination, enriched);
            return;
        }
        try {
            Envelope env = new Envelope();
            env.setDestination(destination);
            env.setInstanceId(instanceId);
            env.setPayload(objectMapper.valueToTree(enriched));
            String json = objectMapper.writeValueAsString(env);
            stringRedisTemplate.convertAndSend(channel, json);
        } catch (Exception e) {
            log.warn("[WsCluster] publish failed, fallback to local send: {}", e.getMessage());
            messagingTemplate.convertAndSend(destination, enriched);
        }
    }

    private Object enrichTrace(String destination, Object payload) {
        String messageType = payload instanceof ChatMessage cm ? cm.getType()
            : (payload != null ? payload.getClass().getSimpleName() : "null");
        if (observabilityMetrics != null) {
            observabilityMetrics.recordWsSend(destination, messageType);
        }

        if (!(payload instanceof ChatMessage msg)) {
            return payload;
        }

        Map<String, Object> trace = buildTraceMeta();
        if (trace.isEmpty()) {
            return payload;
        }

        Map<String, Object> p = msg.getPayload();
        if (p == null) {
            p = new HashMap<>();
            msg.setPayload(p);
        }
        p.put("trace", trace);
        return msg;
    }

    private Map<String, Object> buildTraceMeta() {
        Map<String, Object> meta = new HashMap<>();

        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null) {
                meta.put("traceId", span.context().traceId());
                meta.put("spanId", span.context().spanId());
            }
        }

        AgentTraceContext.Context ctx = AgentTraceContext.get();
        if (ctx != null) {
            if (ctx.conversationId() != null) {
                meta.put("sessionId", String.valueOf(ctx.conversationId()));
            }
            if (ctx.executionId() != null) {
                meta.put("executionId", ctx.executionId());
            }
            if (ctx.nodeName() != null) {
                meta.put("nodeName", ctx.nodeName());
                if (promptVersionService != null) {
                    meta.put("promptVersion", promptVersionService.getCurrentVersion(ctx.nodeName()));
                }
            }
            if (ctx.playlistId() != null) {
                meta.put("playlistId", String.valueOf(ctx.playlistId()));
            }
        }

        return meta;
    }

    public void onClusterMessage(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Envelope env = objectMapper.readValue(json, Envelope.class);
            if (env == null || env.getDestination() == null) {
                return;
            }
            // 仅用于降低噪声：忽略本实例发布的回流（本实例在 cluster 模式下不会本地直发，因此不会丢消息）
            if (env.getInstanceId() != null && env.getInstanceId().equals(instanceId)) {
                // 允许通过配置切换为不忽略，此处保持简单
                // return;
            }
            JsonNode payload = env.getPayload();
            if (payload == null) {
                return;
            }
            messagingTemplate.convertAndSend(env.getDestination(), payload);
        } catch (Exception e) {
            log.debug("[WsCluster] consume failed: {}", e.getMessage());
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Envelope {
        private String destination;
        private String instanceId;

        @JsonProperty("payload")
        private JsonNode payload;
    }
}
