package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.dto.PlaylistRequest;

/**
 * 根据请求参数选择具体的 PlaylistAgentPolicy。
 */
public interface PlaylistAgentPolicySelector {

    /**
     * 基于请求参数选择合适的策略实现。
     *
     * @param request 歌单请求，允许为 null（使用默认策略）
     * @return 选中的策略实现
     */
    PlaylistAgentPolicy selectPolicy(PlaylistRequest request);
}
