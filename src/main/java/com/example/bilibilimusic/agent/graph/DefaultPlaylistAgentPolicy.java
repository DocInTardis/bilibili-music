package com.example.bilibilimusic.agent.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认的 PlaylistAgent 策略实现，对应当前单轮歌单生成流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPlaylistAgentPolicy implements PlaylistAgentPolicy {

    @Override
    public java.util.Set<String> getSupportedModes() {
        return java.util.Collections.singleton("default");
    }

    @Override
    public String getGraphDefinitionResource() {
        return "classpath:graph/playlist-agent-default.yml";
    }

    @Override
    public void configure(PlaylistAgentGraph graph, PlaylistAgentGraphBuilder builder) {
        log.info("[GraphPolicy] 使用 YAML 定义装配状态图: {}", getGraphDefinitionResource());
    }
}
