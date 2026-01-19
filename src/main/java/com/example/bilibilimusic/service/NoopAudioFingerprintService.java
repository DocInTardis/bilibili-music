package com.example.bilibilimusic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认的空实现，用于在未接入实际音频指纹供应商时保持系统可运行。
 *
 * 后续可通过新增具体实现（并标记为 @Primary）接入 ACRCloud / AudD 等服务。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "audio.fingerprint.enabled", havingValue = "false", matchIfMissing = true)
public class NoopAudioFingerprintService implements AudioFingerprintService {

    @Override
    public Integer estimateTrackCount(String videoUrl) {
        // 默认不做任何识别，返回 null，交由时长/标题等启发式逻辑处理
        return null;
    }
}
