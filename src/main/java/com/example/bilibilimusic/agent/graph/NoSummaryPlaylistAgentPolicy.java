package com.example.bilibilimusic.agent.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 无摘要场景策略：
 * - 图结构与默认策略基本一致，但在目标评估后直接结束，不再进入 generate_summary 节点。
 * - 适用于“纯流式播放”“前端自己总结”等不需要 Agent 生成摘要的场景。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoSummaryPlaylistAgentPolicy implements PlaylistAgentPolicy {

    @Override
    public java.util.Set<String> getSupportedModes() {
        return java.util.Collections.singleton("no_summary");
    }

    @Override
    public String getGraphDefinitionResource() {
        return "classpath:graph/playlist-agent-no-summary.yml";
    }

    @Override
    public void configure(PlaylistAgentGraph graph, PlaylistAgentGraphBuilder builder) {
        log.info("[GraphPolicy-NoSummary] 使用 YAML 定义装配状态图: {}", getGraphDefinitionResource());
    }
}
