package com.example.bilibilimusic.service.onlinelearning;

import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class OnlineLearningScoringService {

    private final OnlineLearningConfigService configService;
    private final OnlineLearningExperimentService experimentService;
    private final OnlineLearningModelService modelService;
    private final MeterRegistry meterRegistry;

    public OnlineScore apply(Long userId, Long conversationId, VideoRelevanceScorer.ScoringFeatures features) {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        if (features == null || !cfg.enabled()) {
            return OnlineScore.control();
        }

        OnlineLearningExperimentService.Variant variant = experimentService.assign(userId, conversationId);
        if (variant == OnlineLearningExperimentService.Variant.CONTROL) {
            meterRegistry.counter("online_learning.scoring.variant", "variant", "control").increment();
            return OnlineScore.control();
        }

        OnlineLearningModelService.ModelSnapshot model = modelService.getTreatmentModel(cfg.modelName());
        if (model == null || model.weights() == null || model.weights().isEmpty()) {
            meterRegistry.counter("online_learning.scoring.variant", "variant", "treatment_no_model").increment();
            return new OnlineScore("treatment", cfg.modelName(), model != null ? model.modelVersion() : null, 0, 0.5);
        }

        FeatureVector vector = FeatureVector.from(features);
        double logit = dot(model.weights(), vector.values()) + model.weight("_bias", 0.0);
        double p = sigmoid(logit);
        int adjustment = (int) Math.round((p - 0.5) * cfg.scoreScale());
        meterRegistry.counter("online_learning.scoring.variant", "variant", "treatment").increment();
        return new OnlineScore("treatment", cfg.modelName(), model.modelVersion(), adjustment, p);
    }

    private static double dot(Map<String, Double> w, Map<String, Double> x) {
        if (w == null || x == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (Map.Entry<String, Double> e : x.entrySet()) {
            Double wi = w.get(e.getKey());
            if (wi != null) {
                sum += wi * e.getValue();
            }
        }
        return sum;
    }

    private static double sigmoid(double z) {
        if (z >= 0) {
            double ez = Math.exp(-z);
            return 1.0 / (1.0 + ez);
        }
        double ez = Math.exp(z);
        return ez / (1.0 + ez);
    }

    public record OnlineScore(String variant, String modelName, String modelVersion, int adjustment, double probability) {
        static OnlineScore control() {
            return new OnlineScore("control", null, null, 0, 0.5);
        }
    }

    public record FeatureVector(Map<String, Double> values) {
        static FeatureVector from(VideoRelevanceScorer.ScoringFeatures f) {
            java.util.Map<String, Double> m = new java.util.HashMap<>();
            m.put("titleScore", (double) f.getTitleScore());
            m.put("authorScore", (double) f.getAuthorScore());
            m.put("tagScore", (double) f.getTagScore());
            m.put("descriptionScore", (double) f.getDescriptionScore());
            m.put("semanticScore", (double) f.getSemanticScore());
            m.put("credibilityScore", (double) f.getCredibilityScore());
            m.put("explorationBonus", (double) f.getExplorationBonus());
            m.put("singleArtistBonus", (double) f.getSingleArtistBonus());
            m.put("collaborationAdjust", (double) f.getCollaborationAdjust());
            m.put("collectionPenalty", (double) f.getCollectionPenalty());
            m.put("durationPenalty", (double) f.getDurationPenalty());
            m.put("consecutiveNegativePenalty", (double) f.getConsecutiveNegativePenalty());
            m.put("negativeKeywordHit", f.isNegativeKeywordHit() ? 1.0 : 0.0);
            return new FeatureVector(java.util.Collections.unmodifiableMap(m));
        }
    }
}
