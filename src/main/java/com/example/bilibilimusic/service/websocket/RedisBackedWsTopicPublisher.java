package com.example.bilibilimusic.service.websocket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

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

    @Value("${app.websocket.cluster.enabled:false}")
    private boolean clusterEnabled;

    @Value("${app.websocket.cluster.channel:ws:topic:broadcast}")
    private String channel;

    private final String instanceId = java.util.UUID.randomUUID().toString();

    @Override
    public void send(String destination, Object payload) {
        if (!clusterEnabled) {
            messagingTemplate.convertAndSend(destination, payload);
            return;
        }
        try {
            Envelope env = new Envelope();
            env.setDestination(destination);
            env.setInstanceId(instanceId);
            env.setPayload(objectMapper.valueToTree(payload));
            String json = objectMapper.writeValueAsString(env);
            stringRedisTemplate.convertAndSend(channel, json);
        } catch (Exception e) {
            log.warn("[WsCluster] publish failed, fallback to local send: {}", e.getMessage());
            messagingTemplate.convertAndSend(destination, payload);
        }
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
