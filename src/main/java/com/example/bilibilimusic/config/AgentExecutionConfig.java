package com.example.bilibilimusic.config;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 执行相关的集中配置封装。
 *
 * 目前主要提供：
 * 1. 不同策略下的整体最大执行时长（毫秒）；
 * 2. 不同策略下的默认节点重试次数；
 * 3. 可按策略/节点名覆盖某些节点的最大重试次数。
 *
 * 先以代码形式集中管理，后续如有需要可以再外移到 application.yml。
 */
@Component
public class AgentExecutionConfig {

    // 默认整体最大执行时长（毫秒）
    private static final long DEFAULT_MAX_DURATION_MS = 60_000L;

    // 默认节点级最大重试次数（不含首次尝试）
    private static final int DEFAULT_MAX_NODE_RETRIES = 1;

    /**
     * 获取指定策略下的整体最大执行时长。
     */
    public long getMaxDurationMs(String policyName) {
        // 目前所有策略统一使用默认值，如有需要可以按策略名做区分
        return DEFAULT_MAX_DURATION_MS;
    }

    /**
     * 获取指定策略下的默认节点重试次数。
     */
    public int getDefaultMaxNodeRetries(String policyName) {
        // 目前所有策略统一使用默认值，如有需要可以按策略名做区分
        return DEFAULT_MAX_NODE_RETRIES;
    }

    /**
     * 获取指定策略下，按节点名配置的重试次数覆盖表。
     * key 为 PlaylistAgentGraph 中注册的节点名称。
     */
    public Map<String, Integer> getNodeRetryOverrides(String policyName) {
        Map<String, Integer> overrides = new HashMap<>();
        // 示例：如果后续希望对某些节点特殊化，可以在这里按策略+节点名配置
        // overrides.put("VideoRetrievalNode", 2);
        // overrides.put("GenerateSummaryNode", 0);
        return overrides;
    }
}
