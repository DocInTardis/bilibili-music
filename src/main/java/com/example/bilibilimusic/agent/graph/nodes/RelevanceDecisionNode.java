package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.MusicUnit;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相关性决策节点（RelevanceDecision）
 * 使用打分制判断视频相关性（含偏好加成）
 */
@Slf4j
@RequiredArgsConstructor
public class RelevanceDecisionNode implements AgentNode {
    
    private final VideoRelevanceScorer scorer;
    private final UserPreferenceService preferenceService;

    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        if (index >= state.getSearchResults().size()) {
            state.setShouldContinue(false);
            return NodeResult.success("progress_update");
        }

        VideoInfo video = state.getSearchResults().get(index);
        state.setCurrentStage(PlaylistContext.Stage.CANDIDATE_DECISION);

        UserIntent intent = state.getIntent();
        
        // 获取偏好权重
        Long conversationId = state.getConversationId();
        Map<String, Integer> artistPrefs = preferenceService.getArtistPreferences(conversationId);
        Map<String, Integer> keywordPrefs = preferenceService.getKeywordPreferences(conversationId);
        
        // 使用打分制判断相关性（含偏好加成）
        VideoRelevanceScorer.ScoringResult scoringResult = scorer.scoreVideo(video, intent, artistPrefs, keywordPrefs);
        boolean accepted = scoringResult.isAccepted();
        int score = scoringResult.getScore();
        String decisionReason = scoringResult.getReason();

        Map<String, Object> decisionInfo = new HashMap<>();
        decisionInfo.put("accepted", accepted);
        decisionInfo.put("score", score);
        decisionInfo.put("reason", decisionReason);
        state.setLastDecisionInfo(decisionInfo);
        
        log.debug("[RelDecision] {} - 评分: {}, 结果: {}", 
            video.getTitle(), score, accepted ? "接受" : "拒绝");

        if (accepted) {
            // 从数量估算中读取估算结果
            Map<String, Object> quantity = state.getLastQuantityEstimation();
            int estimatedCount = 1;
            boolean isPlaylist = false;
            if (quantity != null) {
                Object ec = quantity.get("estimatedCount");
                if (ec instanceof Number) {
                    estimatedCount = ((Number) ec).intValue();
                }
                Object ip = quantity.get("isPlaylist");
                if (ip instanceof Boolean) {
                    isPlaylist = (Boolean) ip;
                }
            }

            MusicUnit unit = MusicUnit.builder()
                .title(video.getTitle())
                .artist(video.getAuthor())
                .sourceVideo(video)
                .estimatedCount(estimatedCount)
                .reason(decisionReason)
                .playlistStyle(isPlaylist)
                .build();
            state.getMusicUnits().add(unit);
            state.getSelectedVideos().add(video);
            state.setAccumulatedCount(state.getAccumulatedCount() + estimatedCount);

            // 记录当前视频被接受，以便 VideoAcceptedNode 可以读取
            Map<String, Object> acceptedMeta = new HashMap<>();
            acceptedMeta.put("estimatedCount", estimatedCount);
            state.setLastDecisionInfo(decisionInfo);
        } else {
            state.getTrashVideos().add(video);
        }

        return NodeResult.success(accepted ? "video_accepted" : "progress_update");
    }
}
