package com.example.bilibilimusic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OllamaConfig {

    @Bean
    public WebClient ollamaWebClient(WebClient.Builder builder,
                                     @Value("${ollama.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
