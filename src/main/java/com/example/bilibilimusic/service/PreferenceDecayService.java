package com.example.bilibilimusic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 偏好权重衰减服务
 * 
 * 实现时间衰减机制，模拟人类记忆的遗忘曲线：
 * 1. 指数衰减：权重随时间呈指数下降
 * 2. 半衰期策略：可配置不同场景的半衰期
 * 3. 最小保留：衰减后保留最小权重，避免完全归零
 */
@Service
@Slf4j
public class PreferenceDecayService {
    
    // 默认半衰期：30天（可根据业务调整）
    private static final long DEFAULT_HALF_LIFE_DAYS = 30;
    
    // 最小保留权重比例（原始权重的10%）
    private static final double MIN_WEIGHT_RATIO = 0.1;
    
    /**
     * 计算衰减后的权重
     * 
     * 使用指数衰减公式：W(t) = W₀ × (1/2)^(t/t½)
     * 其中：
     * - W₀: 初始权重
     * - t: 经过的时间（天）
     * - t½: 半衰期（天）
     * 
     * @param originalWeight 原始权重
     * @param lastUpdated 最后更新时间
     * @return 衰减后的权重
     */
    public double calculateDecayedWeight(double originalWeight, LocalDateTime lastUpdated) {
        return calculateDecayedWeight(originalWeight, lastUpdated, DEFAULT_HALF_LIFE_DAYS);
    }
    
    /**
     * 计算衰减后的权重（指定半衰期）
     * 
     * @param originalWeight 原始权重
     * @param lastUpdated 最后更新时间
     * @param halfLifeDays 半衰期（天）
     * @return 衰减后的权重
     */
    public double calculateDecayedWeight(double originalWeight, LocalDateTime lastUpdated, long halfLifeDays) {
        if (originalWeight <= 0) {
            return 0;
        }
        
        // 计算经过的天数
        Duration duration = Duration.between(lastUpdated, LocalDateTime.now());
        double daysPassed = duration.toDays() + duration.toHoursPart() / 24.0;
        
        // 指数衰减公式
        double decayFactor = Math.pow(0.5, daysPassed / halfLifeDays);
        double decayedWeight = originalWeight * decayFactor;
        
        // 保留最小权重
        double minWeight = originalWeight * MIN_WEIGHT_RATIO;
        decayedWeight = Math.max(decayedWeight, minWeight);
        
        log.trace("[Decay] 权重衰减: {} -> {} (天数: {:.2f}, 半衰期: {})", 
            originalWeight, decayedWeight, daysPassed, halfLifeDays);
        
        return decayedWeight;
    }
    
    /**
     * 计算衰减因子（0.0-1.0）
     * 
     * @param lastUpdated 最后更新时间
     * @return 衰减因子
     */
    public double getDecayFactor(LocalDateTime lastUpdated) {
        return getDecayFactor(lastUpdated, DEFAULT_HALF_LIFE_DAYS);
    }
    
    /**
     * 计算衰减因子（指定半衰期）
     * 
     * @param lastUpdated 最后更新时间
     * @param halfLifeDays 半衰期（天）
     * @return 衰减因子（0.0-1.0）
     */
    public double getDecayFactor(LocalDateTime lastUpdated, long halfLifeDays) {
        Duration duration = Duration.between(lastUpdated, LocalDateTime.now());
        double daysPassed = duration.toDays() + duration.toHoursPart() / 24.0;
        
        double decayFactor = Math.pow(0.5, daysPassed / halfLifeDays);
        
        // 保留最小衰减因子
        return Math.max(decayFactor, MIN_WEIGHT_RATIO);
    }
    
    /**
     * 判断是否需要衰减更新
     * 如果权重衰减超过20%，则建议更新数据库
     * 
     * @param lastUpdated 最后更新时间
     * @return 是否需要更新
     */
    public boolean shouldUpdate(LocalDateTime lastUpdated) {
        double decayFactor = getDecayFactor(lastUpdated);
        return decayFactor < 0.8;  // 衰减超过20%
    }
    
    /**
     * 根据行为类型获取推荐的半衰期
     * 
     * @param targetType 目标类型
     * @return 半衰期（天）
     */
    public long getRecommendedHalfLife(String targetType) {
        return switch (targetType) {
            case "video" -> 30;      // 视频偏好：30天半衰期
            case "artist" -> 90;     // 艺人偏好：90天半衰期（更稳定）
            case "keyword" -> 60;    // 关键词偏好：60天半衰期
            default -> DEFAULT_HALF_LIFE_DAYS;
        };
    }
}
