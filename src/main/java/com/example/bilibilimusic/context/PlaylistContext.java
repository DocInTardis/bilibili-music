package com.example.bilibilimusic.context;

import com.example.bilibilimusic.dto.VideoInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
     * 搜索结果（原始）
     */
    private List<VideoInfo> searchResults = new ArrayList<>();
    
    /**
     * 筛选后的视频
     */
    private List<VideoInfo> selectedVideos = new ArrayList<>();
    
    /**
     * 筛选理由
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
    
    /**
     * 执行阶段枚举
     */
    public enum Stage {
        INIT,           // 初始化
        SEARCHING,      // 搜索中
        SEARCHED,       // 搜索完成
        CURATING,       // 筛选中
        CURATED,        // 筛选完成
        SUMMARIZING,    // 总结中
        COMPLETED,      // 完成
        FAILED          // 失败
    }
}
