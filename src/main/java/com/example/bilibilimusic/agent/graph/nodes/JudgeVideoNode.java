package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.skill.CurationSkill;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 视频判断节点（循环节点）
 * 
 * 职责：
 * - 从searchResults中读取当前索引的视频
 * - 调用CurationSkill打分 + LLM判断
 * - 将结果写入selectedVideos或rejectedVideos
 * - 更新currentVideoIndex、accumulatedCount
 */
@Slf4j
@RequiredArgsConstructor
public class JudgeVideoNode implements AgentNode {
    
    private final CurationSkill curationSkill;
    private final VideoRelevanceScorer relevanceScorer;
    private final SimpMessagingTemplate messagingTemplate;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        
        if (index >= state.getSearchResults().size()) {
            log.info("[JudgeNode] 所有视频已处理完毕");
            state.setShouldContinue(false);
            return NodeResult.success("continue_check");
        }
        
        VideoInfo video = state.getSearchResults().get(index);
        log.info("[JudgeNode] 正在判断第 {} 个视频: {}", index + 1, video.getTitle());
        
        // 调用CurationSkill判断
        VideoRelevanceScorer.ScoringResult scoringResult = 
            relevanceScorer.scoreVideo(video, state.getIntent());
        
        boolean accepted = false;
        
        if (scoringResult.isReject()) {
            // 负分直接拒绝
            log.debug("[JudgeNode] 负关键词拒绝: {}", video.getTitle());
            state.getRejectedVideos().add(video);
        } else if (scoringResult.getScore() >= curationSkill.getLlmThresholdHigh()) {
            // 高分直接接受
            log.info("[JudgeNode] 高分接受({}分): {}", scoringResult.getScore(), video.getTitle());
            state.getSelectedVideos().add(video);
            state.setAccumulatedCount(state.getAccumulatedCount() + 1);
            accepted = true;
            pushVideoAccepted(state, video, scoringResult.getScore(), "高分直接接受");
        } else if (scoringResult.getScore() <= curationSkill.getLlmThresholdLow()) {
            // 低分直接拒绝
            log.debug("[JudgeNode] 低分拒绝({}分): {}", scoringResult.getScore(), video.getTitle());
            state.getRejectedVideos().add(video);
        } else {
            // 边界情况，调用LLM
            log.debug("[JudgeNode] 边界情况({}分)，调用LLM: {}", scoringResult.getScore(), video.getTitle());
            boolean llmAccept = curationSkill.judgeVideoWithLLM(video, state.getIntent());
            
            if (llmAccept) {
                state.getSelectedVideos().add(video);
                state.setAccumulatedCount(state.getAccumulatedCount() + 1);
                accepted = true;
                pushVideoAccepted(state, video, scoringResult.getScore(), "LLM边界判断接受");
            } else {
                state.getRejectedVideos().add(video);
            }
        }
        
        // 更新索引
        state.setCurrentVideoIndex(index + 1);
        
        // 检查是否达到目标
        if (state.getAccumulatedCount() >= state.getIntent().getTargetCount()) {
            log.info("[JudgeNode] 已达到目标数量: {}/{}", 
                state.getAccumulatedCount(), state.getIntent().getTargetCount());
            state.setTargetReached(true);
            state.setShouldContinue(false);
        }
        
        return NodeResult.success("continue_check");
    }
    
    private void pushVideoAccepted(PlaylistContext context, VideoInfo video, int score, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bvid", video.getBvid());
        payload.put("title", video.getTitle());
        payload.put("author", video.getAuthor());
        payload.put("duration", video.getDuration());
        payload.put("score", score);
        payload.put("reason", reason);
        payload.put("progress", String.format("%d/%d", 
            context.getAccumulatedCount(), context.getIntent().getTargetCount()));

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("video_accepted")
            .stage("CURATION")
            .content(String.format("✅ 接受: %s", video.getTitle()))
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }
}
