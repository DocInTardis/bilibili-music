package com.example.bilibilimusic.service.onlinelearning;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.OnlineLearningSample;
import com.example.bilibilimusic.entity.UserBehaviorEvent;
import com.example.bilibilimusic.mapper.OnlineLearningSampleMapper;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineLearningSampleService {

    private final OnlineLearningSampleMapper sampleMapper;
    private final OnlineLearningConfigService configService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void recordScoringSample(Long conversationId,
                                    Long userId,
                                    Long playlistId,
                                    UserIntent intent,
                                    VideoInfo video,
                                    VideoRelevanceScorer.ScoringResult scoringResult,
                                    String decisionSource) {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        if (!cfg.trainingEnabled() || video == null || video.getBvid() == null || video.getBvid().isBlank() || scoringResult == null) {
            return;
        }
        try {
            String intentJson = intent != null ? objectMapper.writeValueAsString(intent) : null;
            String featuresJson = scoringResult.getFeatures() != null ? objectMapper.writeValueAsString(scoringResult.getFeatures()) : null;

            OnlineLearningSample sample = OnlineLearningSample.builder()
                .conversationId(conversationId)
                .userId(userId)
                .playlistId(playlistId)
                .sessionId(MDC.get("sessionId"))
                .traceId(MDC.get("traceId"))
                .executionId(MDC.get("executionId"))
                .nodeName(MDC.get("nodeName"))
                .promptVersion(MDC.get("promptVersion"))
                .bvid(video.getBvid())
                .intentJson(intentJson)
                .featuresJson(featuresJson)
                .baseScore(scoringResult.getBaseScore())
                .modelAdjustment(scoringResult.getModelAdjustment())
                .finalScore(scoringResult.getScore())
                .accepted(scoringResult.isAccepted())
                .decisionSource(decisionSource)
                .modelName(scoringResult.getModelName())
                .modelVersion(scoringResult.getModelVersion())
                .variant(scoringResult.getVariant())
                .trained(false)
                .createdAt(LocalDateTime.now())
                .build();

            sampleMapper.insert(sample);
            meterRegistry.counter("online_learning.sample.recorded").increment();
        } catch (Exception e) {
            log.debug("[OnlineLearning] record sample failed: {}", e.getMessage());
        }
    }

    public void tryLabelFromBehavior(UserBehaviorEvent event) {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        if (!cfg.trainingEnabled() || event == null || event.getBehaviorType() == null) {
            return;
        }
        if (!"video".equalsIgnoreCase(event.getTargetType())) {
            return;
        }
        if (event.getConversationId() == null || event.getTargetId() == null || event.getTargetId().isBlank()) {
            return;
        }

        Label label = Label.from(event);
        if (label == null) {
            return;
        }
        try {
            int updated = sampleMapper.labelLatestUnlabeled(event.getConversationId(), event.getTargetId(), label.label(), label.weight(), label.source());
            if (updated > 0) {
                meterRegistry.counter("online_learning.sample.labeled", "label", String.valueOf(label.label())).increment();
            }
        } catch (Exception e) {
            log.debug("[OnlineLearning] label sample failed: {}", e.getMessage());
        }
    }

    public record Label(int label, double weight, String source) {
        static Label from(UserBehaviorEvent e) {
            double intensity = e.getIntensity() != null ? e.getIntensity() : e.getBehaviorType().getDefaultIntensity();
            double abs = Math.max(0.0, Math.min(1.0, Math.abs(intensity)));
            String src = "behavior:" + e.getBehaviorType().name();
            return switch (e.getBehaviorType()) {
                case LIKE -> new Label(1, 1.0 * abs, src);
                case FAVORITE -> new Label(1, 2.0 * abs, src);
                case PLAY_COMPLETE -> new Label(1, 3.0 * abs, src);
                case SHARE -> new Label(1, 3.0 * abs, src);
                case ADD_TO_PLAYLIST -> new Label(1, 2.0 * abs, src);
                case PLAY_PARTIAL -> {
                    if (intensity >= 0.6) yield new Label(1, 1.0 * abs, src);
                    if (intensity <= 0.2) yield new Label(0, 1.0 * (1.0 - intensity), src);
                    yield null;
                }
                case SKIP -> new Label(0, 1.0 * abs, src);
                case REMOVE -> new Label(0, 2.0 * abs, src);
                case DISLIKE -> new Label(0, 3.0 * abs, src);
                case REPORT -> new Label(0, 4.0 * abs, src);
            };
        }
    }
}

