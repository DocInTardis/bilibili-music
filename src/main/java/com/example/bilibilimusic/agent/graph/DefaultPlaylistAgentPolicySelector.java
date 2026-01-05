package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.dto.PlaylistRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 默认的策略选择器：根据请求参数选择具体策略实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPlaylistAgentPolicySelector implements PlaylistAgentPolicySelector {

    private final List<PlaylistAgentPolicy> policies;

    @Override
    public PlaylistAgentPolicy selectPolicy(PlaylistRequest request) {
        String mode = request != null ? request.getMode() : null;
        String normalizedMode = mode != null ? mode.trim().toLowerCase() : "default";

        // 支持多标签模式：例如 "low_cost,no_summary"，形成简单的多场景策略矩阵
        Set<String> tags = Arrays.stream(normalizedMode.split("[,;|+]"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());

        boolean lowCost = tags.contains("low_cost");
        boolean noSummary = tags.contains("no_summary");
            
        PlaylistAgentPolicy policy = null;
        if (noSummary) {
            // “无摘要”场景优先于成本维度：结构差异更大
            policy = findPolicyByModeTag("no_summary");
        }
        if (policy == null && lowCost) {
            policy = findPolicyByModeTag("low_cost");
        }
        if (policy == null) {
            PlaylistAgentPolicy defaultPolicy = findPolicyByModeTag("default");
            PlaylistAgentPolicy lowCostPolicy = findPolicyByModeTag("low_cost");
            if (defaultPolicy != null && lowCostPolicy != null) {
                // 未显式指定结构策略时，简单做一次在线 A/B：在 default 和 low_cost 之间随机分桶
                boolean bucketLowCost = Math.random() < 0.5;
                policy = bucketLowCost ? lowCostPolicy : defaultPolicy;
            } else if (defaultPolicy != null) {
                policy = defaultPolicy;
            } else if (!policies.isEmpty()) {
                policy = policies.get(0);
            }
        }
            
        log.info("[PolicySelector] 选择策略: rawMode={}, tags={}, policy={}", mode, tags,
            policy != null ? policy.getName() : "<none>");
        return policy;
    }
    
    private PlaylistAgentPolicy findPolicyByModeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        for (PlaylistAgentPolicy policy : policies) {
            if (policy.getSupportedModes().contains(tag)) {
                return policy;
            }
        }
        return null;
    }
}
