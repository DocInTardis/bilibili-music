package com.example.bilibilimusic.agent.graph;

import com.example.bilibilimusic.context.PlaylistContext;

/**
 * 条件边 - 决定状态转换的路径
 * 
 * 条件边的职责：
 * - 根据当前 State 判断应该走向哪个节点
 * - 不修改 State，只做判断
 */
@FunctionalInterface
public interface ConditionalEdge {
    
    /**
     * 根据当前状态决定下一个节点
     * 
     * @param state 当前状态
     * @param lastResult 上一个节点的执行结果
     * @return 下一个节点的名称，null 表示结束
     */
    String decide(PlaylistContext state, AgentNode.NodeResult lastResult);

    /**
     * 条件表达式的标准化描述，用于可观测性和调试。
     * 默认返回实现类的简单类名，可由具体边进行覆盖，返回类似：
     * "hasResults && lastResult.success" 或 "shouldContinue && !targetReached"。
     */
    default String getConditionExpression() {
        return this.getClass().getSimpleName();
    }
}
