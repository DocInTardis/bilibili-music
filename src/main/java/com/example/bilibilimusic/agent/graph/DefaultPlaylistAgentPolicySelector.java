package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.dto.PlaylistRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认的策略选择器：根据请求参数选择具体策略实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPlaylistAgentPolicySelector implements PlaylistAgentPolicySelector {

    private final DefaultPlaylistAgentPolicy defaultPlaylistAgentPolicy;
    private final LowCostPlaylistAgentPolicy lowCostPlaylistAgentPolicy;

    @Override
    public PlaylistAgentPolicy selectPolicy(PlaylistRequest request) {
        String mode = request != null ? request.getMode() : null;
        String normalizedMode = mode != null ? mode.trim().toLowerCase() : "default";

        PlaylistAgentPolicy policy;
        if ("low_cost".equals(normalizedMode)) {
            policy = lowCostPlaylistAgentPolicy;
        } else {
            policy = defaultPlaylistAgentPolicy;
        }

        log.info("[PolicySelector] 选择策略: mode={}, policy={}", normalizedMode, policy.getClass().getSimpleName());
        return policy;
    }
}
