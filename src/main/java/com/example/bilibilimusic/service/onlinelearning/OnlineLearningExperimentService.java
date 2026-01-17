package com.example.bilibilimusic.service.onlinelearning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

@Service
@RequiredArgsConstructor
public class OnlineLearningExperimentService {

    private final OnlineLearningConfigService configService;

    public Variant assign(Long userId, Long conversationId) {
        OnlineLearningConfigService.Snapshot cfg = configService.snapshot();
        if (!cfg.enabled() || cfg.treatmentRatio() <= 0) {
            return Variant.CONTROL;
        }
        String key = userId != null ? "u:" + userId : (conversationId != null ? "c:" + conversationId : "unknown");
        long bucket = bucket01(key);
        return bucket < Math.round(cfg.treatmentRatio() * 10_000) ? Variant.TREATMENT : Variant.CONTROL;
    }

    private long bucket01(String key) {
        CRC32 crc32 = new CRC32();
        crc32.update(key.getBytes(StandardCharsets.UTF_8));
        long v = crc32.getValue();
        return v % 10_000;
    }

    public enum Variant {
        CONTROL,
        TREATMENT
    }
}

