package com.example.bilibilimusic.agent.graph;

import java.util.Collections;
import java.util.Set;

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

    /**
     * 策略名称（用于日志和可观测性），默认使用简单类名。
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 支持的模式标签集合，例如 default / low_cost / no_summary 等。
     * 用于策略选择器根据请求 mode 标签进行匹配。
     */
    default Set<String> getSupportedModes() {
        return Collections.emptySet();
    }

    /**
     * 以 YAML/JSON 的形式提供图定义（推荐）。
     * 返回 null 表示仍沿用 configure 中的代码级装配。
     */
    default String getGraphDefinitionResource() {
        return null;
    }
}
