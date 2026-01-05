package com.example.bilibilimusic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 执行相关的集中配置封装。
 *
 * 目前主要提供：
 * 1. 不同策略下的整体最大执行时长（毫秒）；
 * 2. 不同策略下的默认节点重试次数；
 * 3. 可按策略/节点名覆盖某些节点的最大重试次数；
 * 4. 关键路径性能相关阈值（最慢节点、总耗时、LLM 平均耗时等）。
 *
 * 通过 @Value 支持多环境参数隔离，具体值可在 application-*.yml 中按需覆盖。
 */
@Component
public class AgentExecutionConfig {

    // 默认整体最大执行时长（毫秒）
    @Value("${agent.execution.default-max-duration-ms:60000}")
    private long defaultMaxDurationMs;

    // 默认节点级最大重试次数（不含首次尝试）
    @Value("${agent.execution.default-max-node-retries:1}")
    private int defaultMaxNodeRetries;

    // 关键路径性能告警阈值（毫秒）
    @Value("${agent.metrics.slow-node-warn-threshold-ms:2000}")
    private long slowNodeWarnThresholdMs;

    @Value("${agent.metrics.total-exec-warn-threshold-ms:15000}")
    private long totalExecWarnThresholdMs;

    @Value("${agent.metrics.llm-avg-warn-threshold-ms:1500}")
    private long llmAvgWarnThresholdMs;

    /**
     * 获取指定策略下的整体最大执行时长。
     */
    public long getMaxDurationMs(String policyName) {
        // 目前所有策略统一使用默认值，如有需要可以按策略名做区分
        return defaultMaxDurationMs;
    }

    /**
     * 获取指定策略下的默认节点重试次数。
     */
    public int getDefaultMaxNodeRetries(String policyName) {
        // 目前所有策略统一使用默认值，如有需要可以按策略名做区分
        return defaultMaxNodeRetries;
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

    public long getSlowNodeWarnThresholdMs() {
        return slowNodeWarnThresholdMs;
    }

    public long getTotalExecWarnThresholdMs() {
        return totalExecWarnThresholdMs;
    }

    public long getLlmAvgWarnThresholdMs() {
        return llmAvgWarnThresholdMs;
    }
}
