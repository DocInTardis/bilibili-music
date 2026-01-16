package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.UserBehaviorFeedbackService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.service.embedding.SemanticRelevanceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VideoRelevanceScorerTest {

    @Test
    void negativeKeywordShouldReject() {
        UserBehaviorFeedbackService behavior = mock(UserBehaviorFeedbackService.class);
        UserPreferenceService preference = mock(UserPreferenceService.class);
        SemanticRelevanceService semantic = mock(SemanticRelevanceService.class);
        VideoRelevanceScorer scorer = new VideoRelevanceScorer(behavior, preference, semantic);

        UserIntent intent = UserIntent.builder()
            .query("随便来点歌")
            .keywords(List.of("音乐"))
            .build();

        VideoInfo video = VideoInfo.builder()
            .title("周杰伦 教程")
            .author("someone")
            .duration("03:00")
            .build();

        VideoRelevanceScorer.ScoringResult result = scorer.scoreVideo(video, intent);
        assertTrue(result.isReject());
        assertEquals(-100, result.getScore());
        assertTrue(result.getReason().contains("负关键词"));
    }
}
