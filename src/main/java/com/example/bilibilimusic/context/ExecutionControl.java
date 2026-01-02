package com.example.bilibilimusic.context;

import lombok.Data;

/**
 * 执行控制（循环控制变量）
 * 
 * 包含状态机执行过程中的控制标志：
 * 1. 循环索引
 * 2. 继续/终止标志
 * 3. 不需要持久化，由节点逻辑控制
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
     * 重置控制状态
     */
    public void reset() {
        this.currentVideoIndex = 0;
        this.shouldContinue = true;
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
}
