package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.MusicUnit;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.CacheService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相关性决策节点（使用 Redis 缓存和偏好）
 * 使用打分制判断视频相关性（含偏好加成）
 */
@Slf4j
@RequiredArgsConstructor
public class RelevanceDecisionNode implements AgentNode {
    
    private final VideoRelevanceScorer scorer;
    private final UserPreferenceService preferenceService;
    private final CacheService cacheService;

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
        Long conversationId = state.getConversationId();
        Long userId = state.getUserId();
                
        // 尝试从 Redis 缓存获取 LLM 判断结果
        VideoRelevanceScorer.ScoringResult scoringResult = cacheService.getCachedLLMJudgement(video.getBvid(), intent);
                
        if (scoringResult != null) {
            log.debug("[RelDecision] 命中 LLM 缓存: bvid={}, score={}", video.getBvid(), scoringResult.getScore());
        } else {
            // 缓存未命中，从数据库获取偏好权重（含时间衰减）
            Map<String, Integer> artistPrefs = userId != null 
                ? preferenceService.getUserArtistPreferences(userId)
                : preferenceService.getArtistPreferences(conversationId);
            Map<String, Integer> keywordPrefs = userId != null 
                ? preferenceService.getUserKeywordPreferences(userId)
                : preferenceService.getKeywordPreferences(conversationId);
                                
            // 使用打分制判断相关性（含偏好加成 & 探索/冷启动策略）
            scoringResult = scorer.scoreVideo(video, intent, artistPrefs, keywordPrefs, conversationId);
                    
            // 缓存 LLM 判断结果
            cacheService.cacheLLMJudgement(video.getBvid(), intent, scoringResult);
        }
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
