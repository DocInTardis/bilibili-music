package com.example.bilibilimusic.service;

import com.example.bilibilimusic.entity.UserPreference;
import com.example.bilibilimusic.mapper.UserPreferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Value("${DB_ENABLED:true}")
    private boolean dbEnabled;

    private final AtomicBoolean dbAvailable = new AtomicBoolean(true);
    private final AtomicBoolean dbFailureLogged = new AtomicBoolean(false);
    private final Map<Long, Map<String, Integer>> memoryPreferences = new ConcurrentHashMap<>();
    
    // 序列特征参数：最近 N 次交互窗口与增益系数
    private static final int SEQUENTIAL_WINDOW = 5;
    private static final double SEQUENTIAL_ALPHA = 0.5;
    
    /**
     * 增加视频偏好权重（点赞）
     */
    public void likeVideo(Long conversationId, String bvid) {
        adjustPreference(conversationId, "video", bvid, 1);
    }
        
    /**
     * 增加视频偏好权重（收藏）
     */
    public void favoriteVideo(Long conversationId, String bvid) {
        adjustPreference(conversationId, "video", bvid, 2);
    }
        
    /**
     * 增加艺人偏好权重
     */
    public void likeArtist(Long conversationId, String artistName) {
        adjustPreference(conversationId, "artist", artistName, 1);
    }
        
    /**
     * 增加关键词偏好权重
     */
    public void likeKeyword(Long conversationId, String keyword) {
        adjustPreference(conversationId, "keyword", keyword, 1);
    }
        
    /**
     * 通用偏好权重调整方法（支持正负增量）
     */
    public void adjustPreference(Long conversationId, String type, String target, int deltaWeight) {
        if (deltaWeight == 0) {
            return;
        }
        if (!isDbUsable()) {
            updateMemoryPreference(conversationId, type, target, deltaWeight);
            cacheService.incrementPreference(conversationId, type, target, deltaWeight);
            return;
        }

        try {
            UserPreference existing = preferenceMapper.findByConversationAndTypeAndTarget(conversationId, type, target);

            if (existing != null) {
                int oldWeight = existing.getWeightScore() != null ? existing.getWeightScore() : 0;
                int newWeight = oldWeight + deltaWeight;
                existing.setWeightScore(newWeight);
                existing.setInteractionCount(existing.getInteractionCount() + 1);
                existing.setLastUpdated(LocalDateTime.now());
                preferenceMapper.updateById(existing);
                log.debug("[Preference] ????: {} {} (??: {} -> {})",
                    type, target, oldWeight, newWeight);
            } else {
                // ????????????????????
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
                log.debug("[Preference] ????: {} {} (??: {})", type, target, deltaWeight);
            }
        } catch (Exception e) {
            markDbFailed("adjustPreference", e);
        } finally {
            // ??? Redis ZSet???????
            cacheService.incrementPreference(conversationId, type, target, deltaWeight);
        }
    }
        
    /**
     * 获取会话的所有偏好权重映射
     * @return Map<type:target, weight>
     */
    public Map<String, Integer> getPreferenceWeights(Long conversationId) {
        if (!isDbUsable()) {
            return getCachedPreferenceWeights(conversationId);
        }
        try {
            List<UserPreference> preferences = preferenceMapper.findByConversationId(conversationId);
            Map<String, Integer> weights = new HashMap<>();

            for (UserPreference pref : preferences) {
                String key = pref.getPreferenceType() + ":" + pref.getPreferenceTarget();
                long halfLife = decayService.getRecommendedHalfLife(pref.getPreferenceType());
                double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
                weights.put(key, (int) Math.round(decayed));
            }

            return weights;
        } catch (Exception e) {
            markDbFailed("getPreferenceWeights", e);
            return getCachedPreferenceWeights(conversationId);
        }
    }
        
    /**
     * 获取用户维度的所有偏好权重映射（跨会话聚合）
     */
    public Map<String, Integer> getUserPreferenceWeights(Long userId) {
        if (!isDbUsable()) {
            return new HashMap<>();
        }
        try {
            List<UserPreference> preferences = preferenceMapper.findByUserId(userId);
            Map<String, Integer> weights = new HashMap<>();
            for (UserPreference pref : preferences) {
                String key = pref.getPreferenceType() + ":" + pref.getPreferenceTarget();
                long halfLife = decayService.getRecommendedHalfLife(pref.getPreferenceType());
                double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
                weights.put(key, (int) Math.round(decayed));
            }
            return weights;
        } catch (Exception e) {
            markDbFailed("getUserPreferenceWeights", e);
            return new HashMap<>();
        }
    }
            
    /**
     * 获取艺人偏好权重
     */
    public Map<String, Integer> getArtistPreferences(Long conversationId) {
        if (!isDbUsable()) {
            Map<String, Integer> memory = getMemoryPreferencesByType(conversationId, "artist");
            Map<String, Integer> cache = cacheService.getArtistPreferences(conversationId);
            return mergePreferenceMaps(memory, cache);
        }
        try {
            List<UserPreference> preferences = preferenceMapper.findByConversationIdAndType(conversationId, "artist");
            Map<String, Integer> weights = new HashMap<>();

            for (UserPreference pref : preferences) {
                long halfLife = decayService.getRecommendedHalfLife("artist");
                double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
                weights.put(pref.getPreferenceTarget(), (int) Math.round(decayed));
            }

            return weights;
        } catch (Exception e) {
            markDbFailed("getArtistPreferences", e);
            return cacheService.getArtistPreferences(conversationId);
        }
    }
        
    /**
     * 获取用户维度的艺人偏好权重（跨会话聚合）
     */
    public Map<String, Integer> getUserArtistPreferences(Long userId) {
        if (!isDbUsable()) {
            return new HashMap<>();
        }
        try {
            List<UserPreference> preferences = preferenceMapper.findByUserIdAndType(userId, "artist");
            Map<String, Integer> weights = new HashMap<>();
            for (UserPreference pref : preferences) {
                long halfLife = decayService.getRecommendedHalfLife("artist");
                double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
                weights.put(pref.getPreferenceTarget(), (int) Math.round(decayed));
            }
            return weights;
        } catch (Exception e) {
            markDbFailed("getUserArtistPreferences", e);
            return new HashMap<>();
        }
    }
            
    /**
     * 获取关键词偏好权重
     */
    public Map<String, Integer> getKeywordPreferences(Long conversationId) {
        if (!isDbUsable()) {
            Map<String, Integer> memory = getMemoryPreferencesByType(conversationId, "keyword");
            Map<String, Integer> cache = cacheService.getKeywordPreferences(conversationId);
            return mergePreferenceMaps(memory, cache);
        }
        try {
            List<UserPreference> preferences = preferenceMapper.findByConversationIdAndType(conversationId, "keyword");
            Map<String, Integer> weights = new HashMap<>();

            for (UserPreference pref : preferences) {
                long halfLife = decayService.getRecommendedHalfLife("keyword");
                double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
                weights.put(pref.getPreferenceTarget(), (int) Math.round(decayed));
            }

            return weights;
        } catch (Exception e) {
            markDbFailed("getKeywordPreferences", e);
            return cacheService.getKeywordPreferences(conversationId);
        }
    }
        
    /**
     * 获取用户维度的关键词偏好权重（跨会话聚合）
     */
    public Map<String, Integer> getUserKeywordPreferences(Long userId) {
        if (!isDbUsable()) {
            return new HashMap<>();
        }
        try {
            List<UserPreference> preferences = preferenceMapper.findByUserIdAndType(userId, "keyword");
            Map<String, Integer> weights = new HashMap<>();
            for (UserPreference pref : preferences) {
                long halfLife = decayService.getRecommendedHalfLife("keyword");
                double decayed = decayService.calculateDecayedWeight(pref.getWeightScore(), pref.getLastUpdated(), halfLife);
                weights.put(pref.getPreferenceTarget(), (int) Math.round(decayed));
            }
            return weights;
        } catch (Exception e) {
            markDbFailed("getUserKeywordPreferences", e);
            return new HashMap<>();
        }
    }
    
    /**
     * 获取会话维度的所有偏好记录（不包含衰减）
     */
    public List<UserPreference> getAllPreferences(Long conversationId) {
        if (!isDbUsable()) {
            return java.util.Collections.emptyList();
        }
        try {
            return preferenceMapper.findByConversationId(conversationId);
        } catch (Exception e) {
            markDbFailed("getAllPreferences", e);
            return java.util.Collections.emptyList();
        }
    }
    
    /**
     * 获取用户维度的所有偏好记录（跨会话聚合，不包含衰减）
     */
    public List<UserPreference> getAllUserPreferences(Long userId) {
        if (!isDbUsable()) {
            return java.util.Collections.emptyList();
        }
        try {
            return preferenceMapper.findByUserId(userId);
        } catch (Exception e) {
            markDbFailed("getAllUserPreferences", e);
            return java.util.Collections.emptyList();
        }
    }
    
    /**
     * 在衰减权重基础上叠加简单的“序列感”特征
     * 结合交互次数（频率）和最近一次交互时间（新鲜度），近似最近 N 次会话频率
     */
    private boolean isDbUsable() {
        return dbEnabled && dbAvailable.get();
    }

    private void markDbFailed(String op, Exception e) {
        dbAvailable.set(false);
        if (dbFailureLogged.compareAndSet(false, true)) {
            log.warn("[Preference] DB unavailable, fallback to cache. op={}, reason={}", op, e != null ? e.getMessage() : "unknown");
        } else {
            log.debug("[Preference] op={} failed: {}", op, e != null ? e.getMessage() : "unknown");
        }
    }

    private Map<String, Integer> getCachedPreferenceWeights(Long conversationId) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, Integer> memory = getMemoryPreferences(conversationId);
        out.putAll(memory);
        Map<String, Integer> artist = cacheService.getArtistPreferences(conversationId);
        Map<String, Integer> keyword = cacheService.getKeywordPreferences(conversationId);
        for (Map.Entry<String, Integer> e : artist.entrySet()) {
            out.put("artist:" + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : keyword.entrySet()) {
            out.put("keyword:" + e.getKey(), e.getValue());
        }
        return out;
    }

    private void updateMemoryPreference(Long conversationId, String type, String target, int deltaWeight) {
        if (conversationId == null || type == null || target == null) {
            return;
        }
        String key = type + ":" + target.toLowerCase();
        Map<String, Integer> bucket = memoryPreferences.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>());
        bucket.merge(key, deltaWeight, Integer::sum);
    }

    private Map<String, Integer> getMemoryPreferences(Long conversationId) {
        if (conversationId == null) {
            return new HashMap<>();
        }
        Map<String, Integer> bucket = memoryPreferences.get(conversationId);
        return bucket != null ? new HashMap<>(bucket) : new HashMap<>();
    }

    private Map<String, Integer> getMemoryPreferencesByType(Long conversationId, String type) {
        Map<String, Integer> bucket = getMemoryPreferences(conversationId);
        if (bucket.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Integer> result = new HashMap<>();
        String prefix = type + ":";
        for (Map.Entry<String, Integer> entry : bucket.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(prefix)) {
                String target = key.substring(prefix.length());
                result.put(target, entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Integer> mergePreferenceMaps(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new HashMap<>();
        if (a != null) {
            out.putAll(a);
        }
        if (b != null) {
            for (Map.Entry<String, Integer> entry : b.entrySet()) {
                out.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return out;
    }

    private double applySequentialBoost(UserPreference pref, double decayed) {
        if (pref == null || pref.getLastUpdated() == null) {
            return decayed;
        }
        int interactions = pref.getInteractionCount() != null ? pref.getInteractionCount() : 0;
        double freqFactor = Math.min(interactions, SEQUENTIAL_WINDOW) / (double) SEQUENTIAL_WINDOW;
        long days = ChronoUnit.DAYS.between(pref.getLastUpdated(), LocalDateTime.now());
        double recencyFactor = days <= 0 ? 1.0 : 1.0 / (1.0 + days);
        double boost = decayed * freqFactor * recencyFactor * SEQUENTIAL_ALPHA;
        return decayed + boost;
    }
}
