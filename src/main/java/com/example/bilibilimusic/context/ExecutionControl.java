package com.example.bilibilimusic.context;

import lombok.Data;

/**
 * 执行控制（循环控制变量）
 *
 * 包含状态机执行过程中的控制标志：
 * 1. 循环索引
 * 2. 继续/终止标志
 * 3. 当前策略名称及待切换策略
 * 4. 不需要持久化，由节点逻辑控制
 */
@Data
public class ExecutionControl {

    /**
     * 当前正在处理的视频索引
     */
    private int currentVideoIndex = 0;

    /**
     * 是否需要继续处理视频
     */
    private boolean shouldContinue = true;

    /**
     * 当前执行所采用的策略名称（Policy），用于热切换与调试。
     */
    private String strategyName;

    /**
     * 待切换到的策略名称（由节点请求），Graph 在安全时机应用并清空。
     */
    private String pendingStrategyName;

    /**
     * 重置控制状态
     */
    public void reset() {
        this.currentVideoIndex = 0;
        this.shouldContinue = true;
        this.pendingStrategyName = null;
    }

    /**
     * 移动到下一个视频
     */
    public void moveToNextVideo() {
        this.currentVideoIndex++;
    }

    /**
     * 停止继续处理
     */
    public void stop() {
        this.shouldContinue = false;
    }

    /**
     * 请求在后续步骤切换到新的策略。
     */
    public void requestStrategySwitch(String newStrategyName) {
        this.pendingStrategyName = newStrategyName;
    }

    /**
     * 应用策略切换：将 pendingStrategyName 提升为当前策略并清空。
     */
    public void applyStrategySwitchIfNeeded() {
        if (pendingStrategyName != null && !pendingStrategyName.equals(strategyName)) {
            this.strategyName = pendingStrategyName;
            this.pendingStrategyName = null;
        }
    }
}
