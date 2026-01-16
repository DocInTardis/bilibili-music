package com.example.bilibilimusic.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientTimeoutConfig {

    @Bean
    public WebClientCustomizer defaultWebClientTimeoutCustomizer(
        @Value("${http.client.connect-timeout-ms:2000}") int connectTimeoutMs,
        @Value("${http.client.response-timeout-ms:8000}") int responseTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(100, connectTimeoutMs))
            .responseTimeout(Duration.ofMillis(Math.max(100, responseTimeoutMs)));

        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);
        return builder -> builder.clientConnector(connector);
    }
}

