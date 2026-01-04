package com.example.bilibilimusic.service;

import com.example.bilibilimusic.entity.UserPreference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 用户偏好置信度计算服务
 *
 * 置信度计算基于多个维度：
 * 1. 交互次数（频率）：交互越多越可信
 * 2. 时间衰减：最近的偏好更可信
 * 3. 权重一致性：正向权重比负向权重更可信
 * 4. 冷启动惩罚：新偏好置信度低
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreferenceConfidenceService {

    private final PreferenceDecayService decayService;

    // 置信度计算参数
    private static final int CONFIDENCE_MIN_INTERACTIONS = 3;  // 至少3次交互才有基础置信度
    private static final int CONFIDENCE_MAX_INTERACTIONS = 20; // 20次交互后置信度饱和
    private static final int CONFIDENCE_RECENT_DAYS = 30;      // 30天内的交互更可信

    /**
     * 计算单个偏好的置信度（0.0-1.0）
     */
    public double calculateConfidence(UserPreference preference) {
        if (preference == null) {
            return 0.0;
        }

        // 1. 频率因子（基于交互次数）
        int interactions = preference.getInteractionCount() != null ? preference.getInteractionCount() : 0;
        double frequencyFactor = calculateFrequencyFactor(interactions);

        // 2. 时间因子（基于最后更新时间）
        double timeFactor = calculateTimeFactor(preference.getLastUpdated());

        // 3. 一致性因子（基于权重符号）
        double consistencyFactor = calculateConsistencyFactor(preference.getWeightScore());

        // 综合置信度
        double confidence = frequencyFactor * timeFactor * consistencyFactor;

        log.trace("[Confidence] {}:{} -> freq={}, time={}, consistency={}, final={}",
            preference.getPreferenceType(), preference.getPreferenceTarget(),
            frequencyFactor, timeFactor, consistencyFactor, confidence);

        return Math.min(1.0, confidence);
    }

    /**
     * 频率因子：交互次数越多，置信度越高（S型曲线）
     */
    private double calculateFrequencyFactor(int interactions) {
        if (interactions < CONFIDENCE_MIN_INTERACTIONS) {
            // 交互次数太少，置信度很低
            return 0.2 + (0.3 * interactions / (double) CONFIDENCE_MIN_INTERACTIONS);
        } else if (interactions >= CONFIDENCE_MAX_INTERACTIONS) {
            // 交互次数饱和
            return 1.0;
        } else {
            // 线性增长区间
            return 0.5 + (0.5 * (interactions - CONFIDENCE_MIN_INTERACTIONS) /
                (double) (CONFIDENCE_MAX_INTERACTIONS - CONFIDENCE_MIN_INTERACTIONS));
        }
    }

    /**
     * 时间因子：越近的偏好置信度越高
     */
    private double calculateTimeFactor(LocalDateTime lastUpdated) {
        if (lastUpdated == null) {
            return 0.5; // 无时间信息，给中等置信度
        }

        long daysSinceUpdate = ChronoUnit.DAYS.between(lastUpdated, LocalDateTime.now());

        if (daysSinceUpdate <= 7) {
            return 1.0; // 一周内，完全可信
        } else if (daysSinceUpdate <= CONFIDENCE_RECENT_DAYS) {
            // 线性衰减
            return 1.0 - (0.5 * (daysSinceUpdate - 7) / (double) (CONFIDENCE_RECENT_DAYS - 7));
        } else {
            // 超过30天，时间因子降为0.5
            long halfLife = decayService.getRecommendedHalfLife("artist");
            return 0.5 * Math.exp(-0.693 * daysSinceUpdate / (double) halfLife);
        }
    }

    /**
     * 一致性因子：正向偏好比负向偏好更可信
     */
    private double calculateConsistencyFactor(Integer weightScore) {
        if (weightScore == null || weightScore == 0) {
            return 0.5;
        }

        if (weightScore > 0) {
            // 正向偏好：越强越可信
            return Math.min(1.0, 0.7 + (weightScore * 0.05));
        } else {
            // 负向偏好：负得越多，反而可信度降低（可能是误操作）
            return Math.max(0.3, 0.7 + (weightScore * 0.05));
        }
    }

    /**
     * 计算归一化的总体偏好置信度
     *
     * @param preferences 所有偏好记录
     * @return 总体置信度（0.0-1.0）
     */
    public double calculateOverallConfidence(Iterable<UserPreference> preferences) {
        if (preferences == null) {
            return 0.0;
        }

        double sumConfidence = 0.0;
        int count = 0;

        for (UserPreference pref : preferences) {
            double confidence = calculateConfidence(pref);
            sumConfidence += confidence;
            count++;
        }

        if (count == 0) {
            return 0.0;
        }

        // 平均置信度
        double avgConfidence = sumConfidence / count;

        // 样本量修正：偏好数量少时降低整体置信度
        double sampleCorrection = Math.min(1.0, count / 10.0);

        return avgConfidence * sampleCorrection;
    }
}
