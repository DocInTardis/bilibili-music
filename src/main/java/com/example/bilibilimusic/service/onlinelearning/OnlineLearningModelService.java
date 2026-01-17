package com.example.bilibilimusic.service.onlinelearning;

import com.example.bilibilimusic.entity.OnlineLearningModel;
import com.example.bilibilimusic.mapper.OnlineLearningModelMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineLearningModelService {

    private final OnlineLearningModelMapper modelMapper;
    private final OnlineLearningConfigService configService;
    private final ObjectMapper objectMapper;

    public ModelSnapshot getActiveModel(String modelName) {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        String version = cfg.activeModelVersion();
        if (version == null || version.isBlank()) {
            OnlineLearningModel latest = findLatest(modelName);
            if (latest == null) {
                latest = ensureInitialModel(modelName);
            }
            configService.setActiveModelVersion(latest.getModelVersion());
            return toSnapshot(latest);
        }
        OnlineLearningModel row;
        try {
            row = modelMapper.findByNameAndVersion(modelName, version);
        } catch (Exception e) {
            row = null;
        }
        if (row == null) {
            OnlineLearningModel latest = findLatest(modelName);
            if (latest == null) {
                latest = ensureInitialModel(modelName);
            }
            configService.setActiveModelVersion(latest.getModelVersion());
            return toSnapshot(latest);
        }
        return toSnapshot(row);
    }

    public ModelSnapshot getTreatmentModel(String modelName) {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        String treatmentVersion = cfg.treatmentModelVersion();
        if (treatmentVersion != null && !treatmentVersion.isBlank()) {
            OnlineLearningModel row;
            try {
                row = modelMapper.findByNameAndVersion(modelName, treatmentVersion);
            } catch (Exception e) {
                row = null;
            }
            if (row != null) {
                return toSnapshot(row);
            }
        }
        return getActiveModel(modelName);
    }

    public List<OnlineLearningModel> listRecent(String modelName, int limit) {
        try {
            return modelMapper.listRecent(modelName, Math.max(1, limit));
        } catch (Exception e) {
            return List.of();
        }
    }

    public OnlineLearningModel createNewVersion(String modelName, Map<String, Double> weights, int trainedSamples, Map<String, Object> metrics) {
        try {
            String version = "v" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
            OnlineLearningModel row = OnlineLearningModel.builder()
                .modelName(modelName)
                .modelVersion(version)
                .weightsJson(objectMapper.writeValueAsString(weights != null ? weights : Map.of()))
                .trainedSamples(trainedSamples)
                .metricsJson(metrics != null ? objectMapper.writeValueAsString(metrics) : null)
                .createdAt(LocalDateTime.now())
                .build();
            modelMapper.insert(row);
            configService.setActiveModelVersion(version);
            return row;
        } catch (Exception e) {
            throw new RuntimeException("create model version failed", e);
        }
    }

    private OnlineLearningModel ensureInitialModel(String modelName) {
        OnlineLearningModel init = OnlineLearningModel.builder()
            .modelName(modelName)
            .modelVersion("v0")
            .weightsJson("{}")
            .trainedSamples(0)
            .metricsJson("{\"note\":\"init\"}")
            .createdAt(LocalDateTime.now())
            .build();
        try {
            modelMapper.insert(init);
        } catch (Exception ignored) {
        }
        OnlineLearningModel again = modelMapper.findByNameAndVersion(modelName, "v0");
        return again != null ? again : init;
    }

    private OnlineLearningModel findLatest(String modelName) {
        try {
            List<OnlineLearningModel> list = modelMapper.listRecent(modelName, 1);
            return (list != null && !list.isEmpty()) ? list.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ModelSnapshot toSnapshot(OnlineLearningModel row) {
        if (row == null) {
            return new ModelSnapshot(null, null, 0, Collections.emptyMap());
        }
        Map<String, Double> weights = new HashMap<>();
        try {
            if (row.getWeightsJson() != null && !row.getWeightsJson().isBlank()) {
                weights = objectMapper.readValue(row.getWeightsJson(), new TypeReference<>() {});
            }
        } catch (Exception ignored) {
        }
        return new ModelSnapshot(row.getModelName(), row.getModelVersion(), row.getTrainedSamples() != null ? row.getTrainedSamples() : 0, weights);
    }

    public record ModelSnapshot(String modelName, String modelVersion, int trainedSamples, Map<String, Double> weights) {

        public double weight(String name, double def) {
            if (weights == null) {
                return def;
            }
            return weights.getOrDefault(name, def);
        }
    }
}
