package com.example.bilibilimusic.service.onlinelearning;

import com.example.bilibilimusic.mapper.OnlineLearningConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineLearningConfigService {

    private final OnlineLearningConfigMapper configMapper;

    @Value("${onlineLearning.training-enabled:true}")
    private boolean defaultTrainingEnabled;

    @Value("${onlineLearning.enabled:false}")
    private boolean defaultEnabled;

    @Value("${onlineLearning.treatment-ratio:0.0}")
    private double defaultTreatmentRatio;

    @Value("${onlineLearning.model-name:video-relevance-lr-v1}")
    private String defaultModelName;

    @Value("${onlineLearning.learning-rate:0.05}")
    private double defaultLearningRate;

    @Value("${onlineLearning.l2:0.001}")
    private double defaultL2;

    @Value("${onlineLearning.max-batch-size:100}")
    private int defaultMaxBatchSize;

    @Value("${onlineLearning.score-scale:8.0}")
    private double defaultScoreScale;

    private volatile Snapshot cached;
    private volatile long cachedAtMs;

    public Snapshot snapshot() {
        Snapshot cur = cached;
        long now = Instant.now().toEpochMilli();
        if (cur != null && now - cachedAtMs < 3000) {
            return cur;
        }
        Snapshot fresh = load();
        cached = fresh;
        cachedAtMs = now;
        return fresh;
    }

    private Snapshot load() {
        return new Snapshot(
            getBool("trainingEnabled").orElse(defaultTrainingEnabled),
            getBool("enabled").orElse(defaultEnabled),
            clamp01(getDouble("treatmentRatio").orElse(defaultTreatmentRatio)),
            getStr("modelName").orElse(defaultModelName),
            getStr("activeModelVersion").orElse(null),
            getStr("treatmentModelVersion").orElse(null),
            getDouble("learningRate").orElse(defaultLearningRate),
            getDouble("l2").orElse(defaultL2),
            Math.max(1, getInt("maxBatchSize").orElse(defaultMaxBatchSize)),
            Math.max(0.1, getDouble("scoreScale").orElse(defaultScoreScale))
        );
    }

    public void setEnabled(boolean enabled) {
        tryUpsert("enabled", String.valueOf(enabled));
        invalidate();
    }

    public void setTrainingEnabled(boolean enabled) {
        tryUpsert("trainingEnabled", String.valueOf(enabled));
        invalidate();
    }

    public void setTreatmentRatio(double ratio) {
        tryUpsert("treatmentRatio", String.valueOf(clamp01(ratio)));
        invalidate();
    }

    public void setTreatmentModelVersion(String modelVersion) {
        tryUpsert("treatmentModelVersion", modelVersion != null ? modelVersion : "");
        invalidate();
    }

    public void setActiveModelVersion(String modelVersion) {
        tryUpsert("activeModelVersion", modelVersion != null ? modelVersion : "");
        invalidate();
    }

    public void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    private void tryUpsert(String key, String value) {
        try {
            configMapper.upsert(key, value);
        } catch (Exception e) {
            log.debug("[OnlineLearning] config upsert failed: key={}, err={}", key, e.getMessage());
        }
    }

    private Optional<String> getStr(String key) {
        try {
            var row = configMapper.findByKey(key);
            if (row == null || row.getConfigValue() == null) {
                return Optional.empty();
            }
            String v = row.getConfigValue().trim();
            return v.isBlank() ? Optional.empty() : Optional.of(v);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> getBool(String key) {
        return getStr(key).map(s -> "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s));
    }

    private Optional<Double> getDouble(String key) {
        try {
            return getStr(key).map(Double::parseDouble);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Integer> getInt(String key) {
        try {
            return getStr(key).map(Integer::parseInt);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    public record Snapshot(boolean trainingEnabled,
                           boolean enabled,
                           double treatmentRatio,
                           String modelName,
                           String activeModelVersion,
                           String treatmentModelVersion,
                           double learningRate,
                           double l2,
                           int maxBatchSize,
                           double scoreScale) {
    }
}

