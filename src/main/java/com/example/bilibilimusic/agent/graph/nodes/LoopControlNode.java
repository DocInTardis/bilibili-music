package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 循环控制节点（LoopControlNode）
 *
 * 职责：
 * - 推进 currentVideoIndex
 * - 根据 accumulatedCount / targetCount 和索引位置设置 shouldContinue
 * - 不做任何 WebSocket/DB 副作用
 */
@Slf4j
@RequiredArgsConstructor
public class LoopControlNode implements AgentNode {

    @Override
    public NodeResult execute(PlaylistContext state) {
        int nextIndex = state.getCurrentVideoIndex() + 1;
        state.setCurrentVideoIndex(nextIndex);

        int targetCount = state.getIntent().getTargetCount();
        int accumulatedCount = state.getAccumulatedCount();

        boolean shouldContinue = true;
        boolean targetReached = false;
        if (targetCount > 0 && accumulatedCount >= targetCount) {
            shouldContinue = false;
            targetReached = true;
        }
        if (nextIndex >= state.getSearchResults().size()) {
            shouldContinue = false;
        }

        state.setShouldContinue(shouldContinue);
        state.setTargetReached(targetReached);

        log.debug("[LoopControl] nextIndex={}, accumulated={}, target={}, shouldContinue={}",
            nextIndex, accumulatedCount, targetCount, shouldContinue);

        return NodeResult.success(); // 下一个节点由 ContinueJudgeEdge 决定
    }
}
