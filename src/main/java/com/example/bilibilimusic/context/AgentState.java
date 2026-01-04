package com.example.bilibilimusic.context;

import lombok.Data;
import java.io.Serializable;

/**
 * Agent 核心状态（需持久化）
 * 
 * 包含最小必要的状态信息，用于：
 * 1. 断点续跑
 * 2. 跨节点传递
 * 3. Redis 持久化
 */
@Data
public class AgentState implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 会话 ID
     */
    private Long conversationId;
        
    /**
     * 用户 ID（预留，用于跨会话个性化推荐）
     */
    private Long userId;
        
    /**
     * 播放列表 ID
     */
    private Long playlistId;
    
    /**
     * 用户意图
     */
    private UserIntent intent;
    
    /**
     * 当前执行阶段
     */
    private Stage currentStage = Stage.INIT;
    
    /**
     * 已累计的音乐数量
     */
    private int accumulatedCount = 0;
    
    /**
     * 是否已达到目标
     */
    private boolean targetReached = false;
    
    /**
     * 当前处理视频索引（用于断点恢复）
     */
    private int currentVideoIndex = 0;
    
    /**
     * 执行阶段枚举
     */
    public enum Stage {
        INIT,                   // 初始化
        INTENT_UNDERSTANDING,   // 意图理解
        KEYWORD_EXTRACTION,     // 关键词提取
        VIDEO_RETRIEVAL,        // 视频检索
        SEARCHING,              // 搜索中
        SEARCHED,               // 搜索完成
        VIDEO_JUDGEMENT_LOOP,   // 视频判断循环
        CONTENT_ANALYSIS,       // 内容分析
        QUANTITY_ESTIMATION,    // 数量估算
        CANDIDATE_DECISION,     // 采纳决策
        CURATING,               // 策展中
        CURATED,                // 策展完成
        STREAM_FEEDBACK,        // 流式反馈
        TARGET_EVALUATION,      // 目标评估
        PARTIAL_RESULT,         // 部分结果
        SUMMARY_GENERATION,     // 总结生成
        SUMMARIZING,            // 总结中
        COMPLETED,              // 完成
        END,                    // 结束
        FAILED                  // 失败
    }
}
