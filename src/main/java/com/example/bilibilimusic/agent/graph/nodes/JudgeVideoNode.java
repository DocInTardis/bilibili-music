package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.websocket.WsTopicPublisher;
import com.example.bilibilimusic.skill.CurationSkill;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
    private final WsTopicPublisher wsTopicPublisher;
    
    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        
        if (index >= state.getSearchResults().size()) {
            log.info("[JudgeNode] 所有视频已处理完毕");
            state.setShouldContinue(false);
            return NodeResult.success("continue_check");
        }

        int total = state.getSearchResults().size();
        int targetCount = state.getIntent() != null ? state.getIntent().getTargetCount() : 0;

        int batchSize = Integer.parseInt(System.getProperty("agent.judge.batch-size", "8"));
        int endExclusive = Math.min(total, index + Math.max(1, batchSize));

        List<VideoInfo> batch = new ArrayList<>(endExclusive - index);
        for (int i = index; i < endExclusive; i++) {
            batch.add(state.getSearchResults().get(i));
        }

        log.info("[JudgeNode] 并行预评分: index={}..{} / total={}", index + 1, endExclusive, total);

        List<VideoRelevanceScorer.ScoringResult> scoringResults = IntStream.range(0, batch.size())
            .parallel()
            .mapToObj(i -> relevanceScorer.scoreVideo(batch.get(i), state.getIntent()))
            .toList();

        int processed = 0;
        for (int i = 0; i < batch.size(); i++) {
            VideoInfo video = batch.get(i);
            VideoRelevanceScorer.ScoringResult scoringResult = scoringResults.get(i);
            processed++;

            // 如果已经达到目标，直接停止（减少不必要的计算与 LLM 调用）
            if (targetCount > 0 && state.getAccumulatedCount() >= targetCount) {
                state.setTargetReached(true);
                state.setShouldContinue(false);
                break;
            }

            if (scoringResult.isReject()) {
                state.getRejectedVideos().add(video);
                continue;
            }
            if (scoringResult.getScore() >= curationSkill.getLlmThresholdHigh()) {
                state.getSelectedVideos().add(video);
                state.setAccumulatedCount(state.getAccumulatedCount() + 1);
                pushVideoAccepted(state, video, scoringResult.getScore(), "高分直接接受");
                continue;
            }
            if (scoringResult.getScore() <= curationSkill.getLlmThresholdLow()) {
                state.getRejectedVideos().add(video);
                continue;
            }

            // 边界情况：仅在确实还需要补足目标数量时才调用 LLM
            boolean llmAccept = curationSkill.judgeVideoWithLLM(video, state.getIntent());
            if (llmAccept) {
                state.getSelectedVideos().add(video);
                state.setAccumulatedCount(state.getAccumulatedCount() + 1);
                pushVideoAccepted(state, video, scoringResult.getScore(), "LLM边界判断接受");
            } else {
                state.getRejectedVideos().add(video);
            }
        }

        state.setCurrentVideoIndex(index + processed);
        if (state.getCurrentVideoIndex() >= total) {
            state.setShouldContinue(false);
        }
        if (targetCount > 0 && state.getAccumulatedCount() >= targetCount) {
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
        wsTopicPublisher.send("/topic/messages", msg);
    }
}
