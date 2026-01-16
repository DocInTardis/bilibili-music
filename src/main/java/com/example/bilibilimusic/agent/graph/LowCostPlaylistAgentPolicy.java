package com.example.bilibilimusic.agent.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 低成本模式策略：
 * - 使用与默认策略相同的节点与边拓扑
 * - 通过 UserIntent.mode = "low_cost" 配合各 Skill 内部的降级逻辑，减少 LLM 调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowCostPlaylistAgentPolicy implements PlaylistAgentPolicy {

    @Override
    public java.util.Set<String> getSupportedModes() {
        return java.util.Collections.singleton("low_cost");
    }

    @Override
    public String getGraphDefinitionResource() {
        return "classpath:graph/playlist-agent-default.yml";
    }

    @Override
    public void configure(PlaylistAgentGraph graph, PlaylistAgentGraphBuilder builder) {
        log.info("[GraphPolicy-LowCost] 使用 YAML 定义装配状态图: {}", getGraphDefinitionResource());
    }
}
