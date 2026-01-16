package com.example.bilibilimusic.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
public class AgentPrefetchConfig {

    @Value("${agent.prefetch.scoring.enabled:true}")
    private boolean scoringEnabled;

    @Value("${agent.prefetch.scoring.max-videos:50}")
    private int scoringMaxVideos;
}

