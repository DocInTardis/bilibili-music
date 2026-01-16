package com.example.bilibilimusic.config;

import com.example.bilibilimusic.service.websocket.RedisBackedWsTopicPublisher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
@RequiredArgsConstructor
public class WebSocketClusterRedisConfig {

    private final RedisConnectionFactory connectionFactory;
    private final RedisBackedWsTopicPublisher publisher;

    @Value("${app.websocket.cluster.enabled:false}")
    private boolean enabled;

    @Value("${app.websocket.cluster.channel:ws:topic:broadcast}")
    private String channel;

    @org.springframework.context.annotation.Bean
    public RedisMessageListenerContainer wsRedisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        if (enabled) {
            container.addMessageListener((message, pattern) -> {
                String json = message != null && message.getBody() != null
                    ? new String(message.getBody(), StandardCharsets.UTF_8)
                    : null;
                publisher.onClusterMessage(json);
            }, new ChannelTopic(channel));
        }
        return container;
    }
}
