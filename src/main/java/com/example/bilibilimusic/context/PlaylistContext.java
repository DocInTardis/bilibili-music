package com.example.bilibilimusic.context;

import com.example.bilibilimusic.dto.MusicUnit;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 上下文 - 组合模式（解耦后）
 * 
 * 职责分离：
 * 1. AgentState: 核心状态（需持久化）
 * 2. WorkingMemory: 工作内存（运行时数据）
 * 3. ExecutionControl: 执行控制（循环变量）
 * 4. StreamingState: 流式反馈（临时状态）
 * 
 * 优势：
 * - 并发友好：各组件独立，可以并发访问
 * - 持久化高效：只序列化 AgentState
 * - 职责清晰：每个组件有明确边界
 * - 多 Agent 协作：可以共享 WorkingMemory
 */
@Data
public class PlaylistContext {
    
    /**
     * 核心状态（需持久化到 Redis）
     */
    private AgentState state = new AgentState();
    
    /**
     * 工作内存（运行时数据，不需要持久化）
     */
    private WorkingMemory memory = new WorkingMemory();
    
    /**
     * 执行控制（循环控制变量，不需要持久化）
     */
    private ExecutionControl control = new ExecutionControl();
    
    /**
     * 流式反馈状态（临时状态，不需要持久化）
     */
    private StreamingState streaming = new StreamingState();
    
    // ==================== 便捷访问方法（向后兼容）====================
    
    // State 字段的便捷访问
    public Long getConversationId() { return state.getConversationId(); }
    public void setConversationId(Long id) { state.setConversationId(id); }
        
    public Long getUserId() { return state.getUserId(); }
    public void setUserId(Long userId) { state.setUserId(userId); }
        
    public Long getPlaylistId() { return state.getPlaylistId(); }
    public void setPlaylistId(Long id) { state.setPlaylistId(id); }
    
    public UserIntent getIntent() { return state.getIntent(); }
    public void setIntent(UserIntent intent) { state.setIntent(intent); }
    
    public AgentState.Stage getCurrentStage() { return state.getCurrentStage(); }
    public void setCurrentStage(AgentState.Stage stage) { state.setCurrentStage(stage); }
    
    public int getAccumulatedCount() { return state.getAccumulatedCount(); }
    public void setAccumulatedCount(int count) { state.setAccumulatedCount(count); }
    
    public boolean isTargetReached() { return state.isTargetReached(); }
    public void setTargetReached(boolean reached) { state.setTargetReached(reached); }
    
    // Memory 字段的便捷访问
    public List<String> getKeywords() { return memory.getKeywords(); }
    public void setKeywords(List<String> keywords) { memory.setKeywords(keywords); }
    
    public List<VideoInfo> getSearchResults() { return memory.getSearchResults(); }
    public void setSearchResults(List<VideoInfo> results) { memory.setSearchResults(results); }
    
    public List<MusicUnit> getMusicUnits() { return memory.getMusicUnits(); }
    public void setMusicUnits(List<MusicUnit> units) { memory.setMusicUnits(units); }
    
    public List<VideoInfo> getSelectedVideos() { return memory.getSelectedVideos(); }
    public void setSelectedVideos(List<VideoInfo> videos) { memory.setSelectedVideos(videos); }
    
    public List<VideoInfo> getTrashVideos() { return memory.getTrashVideos(); }
    public void setTrashVideos(List<VideoInfo> videos) { memory.setTrashVideos(videos); }
    
    public List<VideoInfo> getRejectedVideos() { return memory.getRejectedVideos(); }
    public void setRejectedVideos(List<VideoInfo> videos) { memory.setRejectedVideos(videos); }
    
    public String getSummary() { return memory.getSummary(); }
    public void setSummary(String summary) { memory.setSummary(summary); }
    
    public String getSelectionReason() { return memory.getSelectionReason(); }
    public void setSelectionReason(String reason) { memory.setSelectionReason(reason); }
    
    // Control 字段的便捷访问
    public int getCurrentVideoIndex() { return control.getCurrentVideoIndex(); }
    public void setCurrentVideoIndex(int index) { control.setCurrentVideoIndex(index); }
    
    public boolean isShouldContinue() { return control.isShouldContinue(); }
    public void setShouldContinue(boolean should) { control.setShouldContinue(should); }
    
    // Streaming 字段的便捷访问
    public Map<String, Object> getLastContentAnalysis() { return streaming.getLastContentAnalysis(); }
    public void setLastContentAnalysis(Map<String, Object> analysis) { streaming.setLastContentAnalysis(analysis); }
    
    public Map<String, Object> getLastQuantityEstimation() { return streaming.getLastQuantityEstimation(); }
    public void setLastQuantityEstimation(Map<String, Object> estimation) { streaming.setLastQuantityEstimation(estimation); }
    
    public Map<String, Object> getLastDecisionInfo() { return streaming.getLastDecisionInfo(); }
    public void setLastDecisionInfo(Map<String, Object> info) { streaming.setLastDecisionInfo(info); }
    
    public boolean isCurrentUnderstandable() { return streaming.isCurrentUnderstandable(); }
    public void setCurrentUnderstandable(boolean understandable) { streaming.setCurrentUnderstandable(understandable); }
    
    /**
     * 兼容旧的 Stage 枚举（类型别名）
     * 直接使用 AgentState.Stage，保持向后兼容
     */
    public static class Stage {
        // 所有 Stage 常量都委托给 AgentState.Stage
        public static final AgentState.Stage INIT = AgentState.Stage.INIT;
        public static final AgentState.Stage INTENT_UNDERSTANDING = AgentState.Stage.INTENT_UNDERSTANDING;
        public static final AgentState.Stage KEYWORD_EXTRACTION = AgentState.Stage.KEYWORD_EXTRACTION;
        public static final AgentState.Stage VIDEO_RETRIEVAL = AgentState.Stage.VIDEO_RETRIEVAL;
        public static final AgentState.Stage SEARCHING = AgentState.Stage.SEARCHING;
        public static final AgentState.Stage SEARCHED = AgentState.Stage.SEARCHED;
        public static final AgentState.Stage VIDEO_JUDGEMENT_LOOP = AgentState.Stage.VIDEO_JUDGEMENT_LOOP;
        public static final AgentState.Stage CONTENT_ANALYSIS = AgentState.Stage.CONTENT_ANALYSIS;
        public static final AgentState.Stage QUANTITY_ESTIMATION = AgentState.Stage.QUANTITY_ESTIMATION;
        public static final AgentState.Stage CANDIDATE_DECISION = AgentState.Stage.CANDIDATE_DECISION;
        public static final AgentState.Stage CURATING = AgentState.Stage.CURATING;
        public static final AgentState.Stage CURATED = AgentState.Stage.CURATED;
        public static final AgentState.Stage STREAM_FEEDBACK = AgentState.Stage.STREAM_FEEDBACK;
        public static final AgentState.Stage TARGET_EVALUATION = AgentState.Stage.TARGET_EVALUATION;
        public static final AgentState.Stage PARTIAL_RESULT = AgentState.Stage.PARTIAL_RESULT;
        public static final AgentState.Stage SUMMARY_GENERATION = AgentState.Stage.SUMMARY_GENERATION;
        public static final AgentState.Stage SUMMARIZING = AgentState.Stage.SUMMARIZING;
        public static final AgentState.Stage COMPLETED = AgentState.Stage.COMPLETED;
        public static final AgentState.Stage END = AgentState.Stage.END;
        public static final AgentState.Stage FAILED = AgentState.Stage.FAILED;
    }
}
