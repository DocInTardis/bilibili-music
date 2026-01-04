package com.example.bilibilimusic.service;

import com.example.bilibilimusic.entity.UserBehaviorEvent;
import com.example.bilibilimusic.entity.UserPreference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;

/**
 * 用户行为反馈服务
 * 
 * 实现完整的"行为 → 偏好 → 决策"闭环：
 * 1. 行为建模：记录和分类用户行为
 * 2. 权重计算：根据行为强度和衰减计算偏好权重
 * 3. 冷启动策略：新用户/新内容的探索机制
 * 4. 探索-利用平衡：平衡推荐准确性和多样性
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserBehaviorFeedbackService {
    
    private final UserPreferenceService preferenceService;
    private final PreferenceDecayService decayService;
    private final CacheService cacheService;
        
    // 冷启动阈值：交互次数少于此值时，启用探索策略
    private static final int COLD_START_THRESHOLD = 10;
    
    // 探索概率（冷启动时）
    private static final double COLD_START_EXPLORATION_RATE = 0.3;
    
    // 正常探索概率
    private static final double NORMAL_EXPLORATION_RATE = 0.1;
    
    private final Random random = new Random();
    
    /**
     * 记录用户行为并更新偏好
     * 
     * @param event 用户行为事件
     */
    public void recordBehavior(UserBehaviorEvent event) {
        log.info("[Behavior] 记录行为: {} {} (强度: {})", 
            event.getBehaviorType(), event.getTargetId(), event.getIntensity());
        
        // 计算权重变化
        double weightDelta = calculateWeightDelta(event);
                
        // 更新偏好权重
        updatePreference(event, weightDelta);
        
        // 更新序列特征（例如连续负向行为计数）
        updateSequentialFeatures(event);
                
        // 根据行为类型执行智能缓存失效（例如负向行为清理相关视频的LLM判断缓存）
        handleSmartCacheInvalidation(event);
                    
        // 标记为已应用
        event.setApplied(true);
    }
        
    /**
     * 更新与行为序列相关的特征（例如连续跳过同一目标）
     */
    private void updateSequentialFeatures(UserBehaviorEvent event) {
        if (event == null || event.getBehaviorType() == null) {
            return;
        }
        String targetType = event.getTargetType();
        String targetId = event.getTargetId();
        if (targetType == null || targetId == null) {
            return;
        }
        // 目前主要关注艺人/关键词的连续负向行为
        if (!"artist".equalsIgnoreCase(targetType) && !"keyword".equalsIgnoreCase(targetType)) {
            return;
        }
        boolean negative = event.getBehaviorType().isNegative();
        cacheService.updateConsecutiveNegativeCount(event.getConversationId(), targetType, targetId, negative);
    }
        
    /**
     * 计算权重变化值
     * 
     * @param event 用户行为事件
     * @return 权重变化（可正可负）
     */
    private double calculateWeightDelta(UserBehaviorEvent event) {
        double baseIntensity = event.getIntensity();
        
        // 根据行为类型调整权重
        double weightDelta = switch (event.getBehaviorType()) {
            case LIKE -> baseIntensity * 10;           // 点赞: +5
            case FAVORITE -> baseIntensity * 15;       // 收藏: +12
            case PLAY_COMPLETE -> baseIntensity * 20;  // 播放完成: +20
            case SHARE -> baseIntensity * 25;          // 分享: +25
            case ADD_TO_PLAYLIST -> baseIntensity * 12; // 加入播放列表: +7.2
            case PLAY_PARTIAL -> baseIntensity * 15;   // 部分播放: 根据完成度
            case SKIP -> baseIntensity * 10;           // 跳过: -3
            case REMOVE -> baseIntensity * 10;         // 删除: -5
            case DISLIKE -> baseIntensity * 15;        // 不喜欢: -9
            case REPORT -> baseIntensity * 20;         // 举报: -20
        };
        
        return weightDelta;
    }
    
    /**
     * 更新偏好权重
     */
    private void updatePreference(UserBehaviorEvent event, double weightDelta) {
        String type = event.getTargetType();
        String target = event.getTargetId();
        Long conversationId = event.getConversationId();
            
        int delta = (int) Math.round(weightDelta);
        if (delta == 0) {
            return;
        }
            
        // 直接通过 UserPreferenceService 调整偏好（支持正负权重）
        preferenceService.adjustPreference(conversationId, type, target, delta);
    }
            
    /**
     * 根据行为类型智能失效相关缓存
     */
    private void handleSmartCacheInvalidation(UserBehaviorEvent event) {
        if (event == null) {
            return;
        }
        UserBehaviorEvent.BehaviorType behaviorType = event.getBehaviorType();
        if (behaviorType == null || !behaviorType.isNegative()) {
            // 目前仅对负向行为做智能失效，正向行为依赖偏好权重调整即可
            return;
        }
        String targetType = event.getTargetType();
        String targetId = event.getTargetId();
        if ("video".equalsIgnoreCase(targetType) && targetId != null && !targetId.isBlank()) {
            // 用户对该视频产生负向反馈，清理该视频的所有LLM判断缓存，下次重新评估
            cacheService.evictLLMJudgementsForVideo(targetId);
        }
    }
            
    /**
     * 判断是否处于冷启动阶段
     * 
     * @param conversationId 会话ID
     * @return 是否冷启动
     */
    public boolean isColdStart(Long conversationId) {
        Map<String, Integer> preferences = preferenceService.getPreferenceWeights(conversationId);
        
        // 统计总交互次数
        int totalInteractions = preferences.values().stream()
            .mapToInt(Integer::intValue)
            .sum();
        
        boolean isCold = totalInteractions < COLD_START_THRESHOLD;
        
        if (isCold) {
            log.debug("[ColdStart] 会话 {} 处于冷启动阶段 (交互次数: {})", 
                conversationId, totalInteractions);
        }
        
        return isCold;
    }
    
    /**
     * 获取探索率（Exploration Rate）
     * 
     * 实现 ε-greedy 策略：
     * - 冷启动时：30% 探索，70% 利用
     * - 正常时：10% 探索，90% 利用
     * 
     * @param conversationId 会话ID
     * @return 探索率（0.0-1.0）
     */
    public double getExplorationRate(Long conversationId) {
        return isColdStart(conversationId) ? 
            COLD_START_EXPLORATION_RATE : 
            NORMAL_EXPLORATION_RATE;
    }
    
    /**
     * 判断当前推荐是否应该探索（而非利用）
     * 
     * @param conversationId 会话ID
     * @return true=探索（推荐多样性内容），false=利用（推荐高偏好内容）
     */
    public boolean shouldExplore(Long conversationId) {
        double explorationRate = getExplorationRate(conversationId);
        boolean explore = random.nextDouble() < explorationRate;
        
        if (explore) {
            log.debug("[Exploration] 触发探索模式 (会话: {})", conversationId);
        }
        
        return explore;
    }
    
    /**
     * 计算带衰减的偏好分数
     * 
     * @param preference 用户偏好
     * @return 衰减后的分数
     */
    public double calculateDecayedScore(UserPreference preference) {
        double originalWeight = preference.getWeightScore();
        LocalDateTime lastUpdated = preference.getLastUpdated();
        
        // 根据类型获取半衰期
        long halfLife = decayService.getRecommendedHalfLife(preference.getPreferenceType());
        
        // 计算衰减后的权重
        return decayService.calculateDecayedWeight(originalWeight, lastUpdated, halfLife);
    }
    
    /**
     * 获取探索加成分数
     * 
     * 在探索模式下，给予新内容额外分数
     * 
     * @param isNewContent 是否是新内容（用户未交互过）
     * @param conversationId 会话ID
     * @return 探索加成分数
     */
    public double getExplorationBonus(boolean isNewContent, Long conversationId) {
        if (!isNewContent) {
            return 0.0;
        }
        
        if (shouldExplore(conversationId)) {
            // 探索模式：给予新内容较大加成
            return isColdStart(conversationId) ? 15.0 : 10.0;
        } else {
            // 利用模式：给予新内容少量加成
            return 2.0;
        }
    }
    
    /**
     * 获取某个目标的连续负向行为计数
     */
    public int getConsecutiveNegativeCount(Long conversationId, String targetType, String targetId) {
        return cacheService.getConsecutiveNegativeCount(conversationId, targetType, targetId);
    }
    
    /**
     * 计算综合偏好分数（考虑衰减、探索、多样性）
     * 
     * @param baseScore 基础分数
     * @param preference 用户偏好（可能为null）
     * @param conversationId 会话ID
     * @return 综合分数
     */
    public double calculateComprehensiveScore(
            double baseScore, 
            UserPreference preference, 
            Long conversationId) {
        
        double finalScore = baseScore;
        
        // 1. 如果有偏好数据，应用衰减
        if (preference != null) {
            double decayedScore = calculateDecayedScore(preference);
            finalScore += decayedScore;
            
            log.trace("[Score] 基础={}, 偏好(衰减后)={}, 总分={}", 
                baseScore, decayedScore, finalScore);
        } else {
            // 2. 新内容：应用探索加成
            double explorationBonus = getExplorationBonus(true, conversationId);
            finalScore += explorationBonus;
            
            log.trace("[Score] 基础={}, 探索加成={}, 总分={}", 
                baseScore, explorationBonus, finalScore);
        }
        
        return finalScore;
    }
}

