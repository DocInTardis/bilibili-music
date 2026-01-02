package com.example.bilibilimusic.context;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 流式反馈状态（临时状态）
 * 
 * 包含用于 WebSocket 流式推送的临时数据：
 * 1. 当前视频分析结果
 * 2. 当前决策信息
 * 3. 不需要持久化，仅用于实时反馈
 */
@Data
public class StreamingState {
    
    /**
     * 当前视频的内容分析结果
     */
    private Map<String, Object> lastContentAnalysis = new HashMap<>();
    
    /**
     * 当前视频的数量估算结果
     */
    private Map<String, Object> lastQuantityEstimation = new HashMap<>();
    
    /**
     * 当前视频的采纳决策信息
     */
    private Map<String, Object> lastDecisionInfo = new HashMap<>();
    
    /**
     * 当前视频是否可理解
     */
    private boolean currentUnderstandable = true;
    
    /**
     * 清理所有临时状态
     */
    public void clear() {
        lastContentAnalysis.clear();
        lastQuantityEstimation.clear();
        lastDecisionInfo.clear();
        currentUnderstandable = true;
    }
}
