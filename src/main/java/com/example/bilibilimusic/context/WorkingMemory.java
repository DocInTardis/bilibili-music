package com.example.bilibilimusic.context;

import com.example.bilibilimusic.dto.MusicUnit;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作内存（运行时数据）
 * 
 * 包含 Agent 执行过程中的中间数据：
 * 1. 检索结果
 * 2. 筛选结果
 * 3. 关键词列表
 * 4. 不需要持久化，可以重新生成
 */
@Data
public class WorkingMemory {
    
    /**
     * 关键词列表
     */
    private List<String> keywords = new ArrayList<>();
    
    /**
     * 搜索结果（原始视频候选池）
     */
    private List<VideoInfo> searchResults = new ArrayList<>();
    
    /**
     * 已确认采纳的音乐单元
     */
    private List<MusicUnit> musicUnits = new ArrayList<>();
    
    /**
     * 已筛选视频列表（从 musicUnits 衍生）
     */
    private List<VideoInfo> selectedVideos = new ArrayList<>();
    
    /**
     * 垃圾桶候选（低置信度或不可理解的视频）
     */
    private List<VideoInfo> trashVideos = new ArrayList<>();
    
    /**
     * 被拒绝的视频列表
     */
    private List<VideoInfo> rejectedVideos = new ArrayList<>();
    
    /**
     * 最终总结
     */
    private String summary;
    
    /**
     * 筛选理由/策略说明（用于前端显示）
     */
    private String selectionReason;
}
