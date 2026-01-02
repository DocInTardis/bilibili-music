package com.example.bilibilimusic.agent.graph;

/**
 * PlaylistAgent 的策略接口，用于按场景装配节点和边。
 */
public interface PlaylistAgentPolicy {

    /**
     * 根据策略配置状态图：添加节点、条件边并设置起始节点。
     *
     * @param graph   要配置的 PlaylistAgentGraph 实例
     * @param builder 依赖提供方（访问各类 Skill / Service）
     */
    void configure(PlaylistAgentGraph graph, PlaylistAgentGraphBuilder builder);
}
