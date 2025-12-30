package com.example.bilibilimusic.context;

import com.example.bilibilimusic.dto.VideoInfo;
import lombok.Data;

/**
 * Agent 上下文 - 管理歌单生成过程中的状态与数据
 * 这是 Agent 的"短期记忆"
 */
@Data
public class PlaylistContext {
    
    /**
     * 用户意图
     */
    private UserIntent intent;
    
    /**
     * 当前会话ID（用于数据库持久化）
     */
    private Long conversationId;
    
    /**
     * 当前播放列表ID（用于数据库持久化）
     */
    private Long playlistId;
    
    /**
     * 关键词列表
     */
    private java.util.List<String> keywords = new java.util.ArrayList<>();
    
    /**
     * 搜索结果（原始视频候选池）
     */
    private java.util.List<VideoInfo> searchResults = new java.util.ArrayList<>();
    
    /**
     * 已确认采纳的音乐单元
     */
    private java.util.List<com.example.bilibilimusic.dto.MusicUnit> musicUnits = new java.util.ArrayList<>();

    /**
     * 兼容旧逻辑的“已筛选视频列表”，从 musicUnits 衍生
     */
    private java.util.List<VideoInfo> selectedVideos = new java.util.ArrayList<>();

    /**
     * 垃圾桶候选（低置信度或不可理解的视频）
     */
    private java.util.List<VideoInfo> trashVideos = new java.util.ArrayList<>();
    
    /**
     * 被拒绝的视频列表（未通过筛选）
     */
    private java.util.List<VideoInfo> rejectedVideos = new java.util.ArrayList<>();
    
    /**
     * 当前视频的内容分析结果（用于流式反馈）
     */
    private java.util.Map<String, Object> lastContentAnalysis;
    
    /**
     * 当前视频的数量估算结果（用于流式反馈）
     */
    private java.util.Map<String, Object> lastQuantityEstimation;
    
    /**
     * 当前视频的采纳决策信息（用于流式反馈）
     */
    private java.util.Map<String, Object> lastDecisionInfo;
    
    /**
     * 当前视频是否可理解（由 ContentAnalysis 节点写入）
     */
    private boolean currentUnderstandable = true;
    /**
     * 为前端解释用的筛选理由 / 总体策略说明
     */
    private String selectionReason;
    
    /**
     * 最终总结
     */
    private String summary;
    
    /**
     * 当前阶段
     */
    private Stage currentStage = Stage.INIT;
    
    // ==================== 状态机循环控制字段 ====================
    
    /**
     * 当前正在处理的视频索引
     */
    private int currentVideoIndex = 0;
    
    /**
     * 已累计的音乐数量
     */
    private int accumulatedCount = 0;
    
    /**
     * 是否已达到目标数量
     */
    private boolean targetReached = false;
    
    /**
     * 是否需要继续处理视频
     */
    private boolean shouldContinue = true;
    
    /**
     * 执行阶段枚举
     */
    public enum Stage {
        INIT,                   // 初始化
        INTENT_UNDERSTANDING,   // 意图理解
        KEYWORD_EXTRACTION,     // 关键词拆解
        VIDEO_RETRIEVAL,        // 视频检索
        VIDEO_JUDGEMENT_LOOP,   // 视频逐个判断大循环
        CONTENT_ANALYSIS,       // 内容可理解性分析
        QUANTITY_ESTIMATION,    // 音乐数量估算
        CANDIDATE_DECISION,     // 是否采纳决策
        STREAM_FEEDBACK,        // 流式反馈
        TARGET_EVALUATION,      // 目标评估
        PARTIAL_RESULT,         // 未达标时的部分结果
        SUMMARY,                // 总结
        SUMMARY_GENERATION,     // 总结生成（状态机节点用）
        END,                    // 结束
        // 兼容旧阶段命名（仍可能被部分 Skill 使用）
        SEARCHING,              // 搜索中
        SEARCHED,               // 搜索完成
        CURATING,               // 筛选中
        CURATED,                // 筛选完成
        SUMMARIZING,            // 总结中
        COMPLETED,              // 完成
        FAILED                  // 失败
    }
}
