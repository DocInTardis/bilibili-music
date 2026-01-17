package com.example.bilibilimusic.service.onlinelearning;

import com.example.bilibilimusic.entity.OnlineLearningSample;
import com.example.bilibilimusic.mapper.OnlineLearningSampleMapper;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineLearningTrainingWorker {

    private static final String TRAIN_LOCK = "lock:online-learning:train";

    private final OnlineLearningConfigService configService;
    private final OnlineLearningModelService modelService;
    private final OnlineLearningSampleMapper sampleMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${onlineLearning.train-interval-ms:4000}")
    public void trainIfNeeded() {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        if (!cfg.trainingEnabled()) {
            return;
        }
        if (!tryAcquireLock()) {
            return;
        }
        try {
            int max = cfg.maxBatchSize();
            List<OnlineLearningSample> batch = sampleMapper.listLabeledNotTrained(max);
            if (batch == null || batch.isEmpty()) {
                return;
            }

            OnlineLearningModelService.ModelSnapshot base = modelService.getActiveModel(cfg.modelName());
            TrainingOutcome out = trainOneBatch(cfg, base, batch);
            if (out.usedSamples() <= 0) {
                for (OnlineLearningSample s : batch) {
                    if (s != null && s.getId() != null) {
                        try {
                            sampleMapper.markTrained(s.getId(), base.modelVersion() + ":skipped");
                        } catch (Exception ignored) {
                        }
                    }
                }
                return;
            }

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("avgLoss", out.avgLoss());
            metrics.put("trainedInBatch", out.usedSamples());
            metrics.put("timestampMs", Instant.now().toEpochMilli());

            var newModel = modelService.createNewVersion(cfg.modelName(), out.weights(), base.trainedSamples() + out.usedSamples(), metrics);
            for (OnlineLearningSample s : out.trainedSamples()) {
                if (s != null && s.getId() != null) {
                    try {
                        sampleMapper.markTrained(s.getId(), newModel.getModelVersion());
                    } catch (Exception ignored) {
                    }
                }
            }
            for (OnlineLearningSample s : out.invalidSamples()) {
                if (s != null && s.getId() != null) {
                    try {
                        sampleMapper.markTrained(s.getId(), newModel.getModelVersion() + ":invalid");
                    } catch (Exception ignored) {
                    }
                }
            }

            meterRegistry.counter("online_learning.train.batch").increment();
            meterRegistry.counter("online_learning.train.samples").increment(out.usedSamples());
        } catch (Exception e) {
            log.debug("[OnlineLearning] training failed: {}", e.getMessage());
        } finally {
            releaseLock();
        }
    }

    private TrainingOutcome trainOneBatch(OnlineLearningConfigService.Snapshot cfg,
                                         OnlineLearningModelService.ModelSnapshot base,
                                         List<OnlineLearningSample> batch) {
        Map<String, Double> w = new HashMap<>();
        if (base.weights() != null) {
            w.putAll(base.weights());
        }
        double lr = cfg.learningRate();
        double l2 = cfg.l2();

        int used = 0;
        double lossSum = 0.0;
        java.util.ArrayList<OnlineLearningSample> trained = new java.util.ArrayList<>();
        java.util.ArrayList<OnlineLearningSample> invalid = new java.util.ArrayList<>();

        for (OnlineLearningSample sample : batch) {
            if (sample == null) {
                continue;
            }
            Integer label = sample.getLabel();
            if (label == null) {
                continue;
            }
            VideoRelevanceScorer.ScoringFeatures f = parseFeatures(sample.getFeaturesJson());
            if (f == null) {
                invalid.add(sample);
                continue;
            }

            Map<String, Double> x = OnlineLearningScoringService.FeatureVector.from(f).values();
            double bias = w.getOrDefault("_bias", 0.0);
            double z = dot(w, x) + bias;
            double p = sigmoid(z);
            double y = label == 1 ? 1.0 : 0.0;
            double weight = sample.getLabelWeight() != null ? sample.getLabelWeight() : 1.0;
            weight = Math.max(0.1, Math.min(5.0, weight));

            double err = (p - y);
            for (Map.Entry<String, Double> e : x.entrySet()) {
                String k = e.getKey();
                double xi = e.getValue();
                double wi = w.getOrDefault(k, 0.0);
                double grad = err * xi + l2 * wi;
                w.put(k, wi - lr * weight * grad);
            }
            w.put("_bias", bias - lr * weight * err);

            lossSum += binaryCrossEntropy(y, p);
            used++;
            trained.add(sample);
        }
        return new TrainingOutcome(w, used, used > 0 ? lossSum / used : 0.0, trained, invalid);
    }

    private VideoRelevanceScorer.ScoringFeatures parseFeatures(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, VideoRelevanceScorer.ScoringFeatures.class);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean tryAcquireLock() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(TRAIN_LOCK, "1", 30, TimeUnit.SECONDS));
        } catch (Exception e) {
            return true;
        }
    }

    private void releaseLock() {
        try {
            redis.delete(TRAIN_LOCK);
        } catch (Exception ignored) {
        }
    }

    private static double dot(Map<String, Double> w, Map<String, Double> x) {
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

    private static double binaryCrossEntropy(double y, double p) {
        double eps = 1e-9;
        double pp = Math.max(eps, Math.min(1.0 - eps, p));
        return -(y * Math.log(pp) + (1.0 - y) * Math.log(1.0 - pp));
    }

    private record TrainingOutcome(Map<String, Double> weights,
                                   int usedSamples,
                                   double avgLoss,
                                   List<OnlineLearningSample> trainedSamples,
                                   List<OnlineLearningSample> invalidSamples) {
    }
}
