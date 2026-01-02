package com.example.bilibilimusic.service;

import com.example.bilibilimusic.entity.UserPreference;
import com.example.bilibilimusic.mapper.UserPreferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好服务 - 记录和学习用户偏好（同步到 Redis）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserPreferenceService {
    
    private final UserPreferenceMapper preferenceMapper;
    private final CacheService cacheService;
    private final PreferenceDecayService decayService;
    
    /**
     * 增加视频偏好权重（点赞）
     */
    public void likeVideo(Long conversationId, String bvid) {
        increasePreference(conversationId, "video", bvid, 1);
        cacheService.incrementPreference(conversationId, "video", bvid, 1);
    }
    
    /**
     * 增加视频偏好权重（收藏）
     */
    public void favoriteVideo(Long conversationId, String bvid) {
        increasePreference(conversationId, "video", bvid, 2);
        cacheService.incrementPreference(conversationId, "video", bvid, 2);
    }
    
    /**
     * 增加艺人偏好权重
     */
    public void likeArtist(Long conversationId, String artistName) {
        increasePreference(conversationId, "artist", artistName, 1);
        cacheService.incrementPreference(conversationId, "artist", artistName, 1);
    }
    
    /**
     * 增加关键词偏好权重
     */
    public void likeKeyword(Long conversationId, String keyword) {
        increasePreference(conversationId, "keyword", keyword, 1);
        cacheService.incrementPreference(conversationId, "keyword", keyword, 1);
    }
    
    /**
     * 通用增加偏好权重方法
     */
    private void increasePreference(Long conversationId, String type, String target, int deltaWeight) {
        UserPreference existing = preferenceMapper.findByConversationAndTypeAndTarget(conversationId, type, target);
        
        if (existing != null) {
            // 更新现有偏好
            existing.setWeightScore(existing.getWeightScore() + deltaWeight);
            existing.setInteractionCount(existing.getInteractionCount() + 1);
            existing.setLastUpdated(LocalDateTime.now());
            preferenceMapper.updateById(existing);
            log.debug("[Preference] 更新偏好: {} {} (权重: {} -> {})", 
                type, target, existing.getWeightScore() - deltaWeight, existing.getWeightScore());
        } else {
            // 创建新偏好
            UserPreference newPref = UserPreference.builder()
                .conversationId(conversationId)
                .preferenceType(type)
                .preferenceTarget(target)
                .weightScore(deltaWeight)
                .interactionCount(1)
                .createdAt(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
            preferenceMapper.insert(newPref);
            log.debug("[Preference] 新增偏好: {} {} (权重: {})", type, target, deltaWeight);
        }
    }
    
    /**
     * 获取会话的所有偏好权重映射
     * @return Map<type:target, weight>
     */
    public Map<String, Integer> getPreferenceWeights(Long conversationId) {
        List<UserPreference> preferences = preferenceMapper.findByConversationId(conversationId);
        Map<String, Integer> weights = new HashMap<>();
            
        for (UserPreference pref : preferences) {
            String key = pref.getPreferenceType() + ":" + pref.getPreferenceTarget();
            long halfLife = decayService.getRecommendedHalfLife(pref.getPreferenceType());
            double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
            weights.put(key, (int) Math.round(decayed));
        }
            
        return weights;
    }
        
    /**
     * 获取艺人偏好权重
     */
    public Map<String, Integer> getArtistPreferences(Long conversationId) {
        List<UserPreference> preferences = preferenceMapper.findByConversationIdAndType(conversationId, "artist");
        Map<String, Integer> weights = new HashMap<>();
            
        for (UserPreference pref : preferences) {
            long halfLife = decayService.getRecommendedHalfLife("artist");
            double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
            weights.put(pref.getPreferenceTarget(), (int) Math.round(decayed));
        }
            
        return weights;
    }
        
    /**
     * 获取关键词偏好权重
     */
    public Map<String, Integer> getKeywordPreferences(Long conversationId) {
        List<UserPreference> preferences = preferenceMapper.findByConversationIdAndType(conversationId, "keyword");
        Map<String, Integer> weights = new HashMap<>();
        
        for (UserPreference pref : preferences) {
            long halfLife = decayService.getRecommendedHalfLife("keyword");
            double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
            weights.put(pref.getPreferenceTarget(), (int) Math.round(decayed));
        }
        
        return weights;
    }
}
