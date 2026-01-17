package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.mapper.OnlineLearningSampleMapper;
import com.example.bilibilimusic.service.onlinelearning.OnlineLearningConfigService;
import com.example.bilibilimusic.service.onlinelearning.OnlineLearningModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playlist/online-learning")
@RequiredArgsConstructor
public class OnlineLearningController {

    private final OnlineLearningConfigService configService;
    private final OnlineLearningModelService modelService;
    private final OnlineLearningSampleMapper sampleMapper;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        Map<String, Object> out = new HashMap<>();
        out.put("config", cfg);
        out.put("activeModel", modelService.getActiveModel(cfg.modelName()));
        out.put("treatmentModel", modelService.getTreatmentModel(cfg.modelName()));
        try {
            out.put("stats", sampleMapper.aggregate());
        } catch (Exception e) {
            out.put("stats", Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        if (body != null) {
            if (body.containsKey("enabled")) {
                configService.setEnabled(bool(body.get("enabled")));
            }
            if (body.containsKey("trainingEnabled")) {
                configService.setTrainingEnabled(bool(body.get("trainingEnabled")));
            }
            if (body.containsKey("treatmentRatio")) {
                configService.setTreatmentRatio(doubleVal(body.get("treatmentRatio")));
            }
            if (body.containsKey("activeModelVersion")) {
                configService.setActiveModelVersion(str(body.get("activeModelVersion")));
            }
            if (body.containsKey("treatmentModelVersion")) {
                configService.setTreatmentModelVersion(str(body.get("treatmentModelVersion")));
            }
            configService.invalidate();
        }
        return ResponseEntity.ok(Map.of("config", configService.snapshot()));
    }

    @GetMapping("/models")
    public ResponseEntity<List<?>> listModels(@RequestParam(defaultValue = "10") int limit) {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        return ResponseEntity.ok(modelService.listRecent(cfg.modelName(), Math.max(1, limit)));
    }

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@RequestParam String version) {
        configService.setActiveModelVersion(version);
        configService.invalidate();
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        return ResponseEntity.ok(Map.of("activeModel", modelService.getActiveModel(cfg.modelName())));
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static double doubleVal(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }
}

