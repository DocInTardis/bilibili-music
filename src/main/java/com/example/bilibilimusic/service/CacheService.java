package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 缓存服务 - 统一管理 Redis 缓存
 * 
 * 缓存策略：
 * 1. Query 级缓存：相同/相似 query、关键词
 * 2. LLM 判断结果缓存：视频+意图的判断结果
 * 3. 用户偏好 ZSet 缓存：个性化推荐权重
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    
    // 缓存 TTL 配置（秒）
    private static final long QUERY_CACHE_TTL = 3600;        // 1小时
    private static final long KEYWORD_CACHE_TTL = 7200;      // 2小时
    private static final long LLM_RESULT_CACHE_TTL = 86400;  // 24小时
    private static final long PREFERENCE_CACHE_TTL = 604800; // 7天
    
    // ==================== 1. Query 级缓存 ====================
    
    /**
     * 生成查询缓存 Key
     */
    public String generateQueryCacheKey(String query) {
        String normalized = normalizeQuery(query);
        return "query:cache:" + md5(normalized);
    }
    
    /**
     * 缓存搜索结果
     */
    public void cacheSearchResults(String query, List<VideoInfo> videos) {
        try {
            String key = generateQueryCacheKey(query);
            String json = objectMapper.writeValueAsString(videos);
            stringRedisTemplate.opsForValue().set(key, json, QUERY_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 缓存搜索结果: query={}, videos={}", query, videos.size());
        } catch (JsonProcessingException e) {
            log.warn("[Cache] 序列化搜索结果失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取缓存的搜索结果
     */
    @SuppressWarnings("unchecked")
    public List<VideoInfo> getCachedSearchResults(String query) {
        try {
            String key = generateQueryCacheKey(query);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                List<VideoInfo> videos = objectMapper.readValue(json, 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, VideoInfo.class));
                log.debug("[Cache] 命中搜索结果缓存: query={}, videos={}", query, videos.size());
                return videos;
            }
        } catch (Exception e) {
            log.warn("[Cache] 反序列化搜索结果失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 缓存关键词提取结果
     */
    public void cacheKeywords(String query, List<String> keywords) {
        try {
            String key = "keywords:" + md5(normalizeQuery(query));
            String json = objectMapper.writeValueAsString(keywords);
            stringRedisTemplate.opsForValue().set(key, json, KEYWORD_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 缓存关键词: query={}, keywords={}", query, keywords);
        } catch (JsonProcessingException e) {
            log.warn("[Cache] 序列化关键词失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取缓存的关键词
     */
    @SuppressWarnings("unchecked")
    public List<String> getCachedKeywords(String query) {
        try {
            String key = "keywords:" + md5(normalizeQuery(query));
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                List<String> keywords = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                log.debug("[Cache] 命中关键词缓存: query={}, keywords={}", query, keywords);
                return keywords;
            }
        } catch (Exception e) {
            log.warn("[Cache] 反序列化关键词失败: {}", e.getMessage());
        }
        return null;
    }
    
    // ==================== 2. LLM 判断结果缓存 ====================
    
    /**
     * 生成 LLM 判断缓存 Key
     */
    public String generateLLMCacheKey(String bvid, UserIntent intent) {
        // 使用 bvid + 意图摘要生成唯一 key
        String intentSummary = generateIntentSummary(intent);
        return "llm:judge:" + bvid + ":" + md5(intentSummary);
    }
    
    /**
     * 缓存 LLM 判断结果
     */
    public void cacheLLMJudgement(String bvid, UserIntent intent, VideoRelevanceScorer.ScoringResult result) {
        try {
            String key = generateLLMCacheKey(bvid, intent);
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(key, json, LLM_RESULT_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 缓存LLM判断: bvid={}, score={}", bvid, result.getScore());
        } catch (JsonProcessingException e) {
            log.warn("[Cache] 序列化LLM判断结果失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取缓存的 LLM 判断结果
     */
    public VideoRelevanceScorer.ScoringResult getCachedLLMJudgement(String bvid, UserIntent intent) {
        try {
            String key = generateLLMCacheKey(bvid, intent);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                VideoRelevanceScorer.ScoringResult result = objectMapper.readValue(json, VideoRelevanceScorer.ScoringResult.class);
                log.debug("[Cache] 命中LLM判断缓存: bvid={}, score={}", bvid, result.getScore());
                return result;
            }
        } catch (Exception e) {
            log.warn("[Cache] 反序列化LLM判断结果失败: {}", e.getMessage());
        }
        return null;
    }
    
    // ==================== 3. 用户偏好 ZSet 缓存 ====================
    
    /**
     * 获取用户偏好缓存 Key
     */
    public String getUserPreferenceKey(Long conversationId) {
        return "user:preference:" + conversationId;
    }
    
    /**
     * 增加用户偏好权重（ZSet）
     */
    public void incrementPreference(Long conversationId, String preferenceType, String target, int deltaWeight) {
        String key = getUserPreferenceKey(conversationId);
        String member = preferenceType + ":" + target.toLowerCase();
        
        // 使用 ZSet 的 incrementScore
        Double newScore = redisTemplate.opsForZSet().incrementScore(key, member, deltaWeight);
        
        // 设置过期时间
        redisTemplate.expire(key, PREFERENCE_CACHE_TTL, TimeUnit.SECONDS);
        
        log.debug("[Cache] 增加用户偏好: conversationId={}, {}={}, newWeight={}", 
            conversationId, preferenceType, target, newScore);
    }
    
    /**
     * 获取艺人偏好权重（从 ZSet）
     */
    public Map<String, Integer> getArtistPreferences(Long conversationId) {
        return getPreferencesByType(conversationId, "artist");
    }
    
    /**
     * 获取关键词偏好权重（从 ZSet）
     */
    public Map<String, Integer> getKeywordPreferences(Long conversationId) {
        return getPreferencesByType(conversationId, "keyword");
    }
    
    /**
     * 根据类型获取偏好权重
     */
    private Map<String, Integer> getPreferencesByType(Long conversationId, String type) {
        String key = getUserPreferenceKey(conversationId);
        String pattern = type + ":*";
        
        // 获取所有成员和分数
        Set<ZSetOperations.TypedTuple<Object>> tuples = redisTemplate.opsForZSet()
            .rangeWithScores(key, 0, -1);
        
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, Integer> result = new HashMap<>();
        for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
            String member = (String) tuple.getValue();
            if (member != null && member.startsWith(type + ":")) {
                String target = member.substring(type.length() + 1);
                Integer weight = tuple.getScore() != null ? tuple.getScore().intValue() : 0;
                result.put(target, weight);
            }
        }
        
        log.debug("[Cache] 获取用户偏好: conversationId={}, type={}, count={}", 
            conversationId, type, result.size());
        
        return result;
    }
    
    /**
     * 获取 Top N 偏好
     */
    public Map<String, Integer> getTopPreferences(Long conversationId, int topN) {
        String key = getUserPreferenceKey(conversationId);
        
        // 按分数倒序获取 Top N
        Set<ZSetOperations.TypedTuple<Object>> tuples = redisTemplate.opsForZSet()
            .reverseRangeWithScores(key, 0, topN - 1);
        
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyMap();
        }
        
        return tuples.stream()
            .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
            .collect(Collectors.toMap(
                tuple -> (String) tuple.getValue(),
                tuple -> tuple.getScore().intValue(),
                (a, b) -> a,
                LinkedHashMap::new
            ));
    }
    
    /**
     * 清除用户偏好缓存
     */
    public void clearUserPreference(Long conversationId) {
        String key = getUserPreferenceKey(conversationId);
        redisTemplate.delete(key);
        log.debug("[Cache] 清除用户偏好缓存: conversationId={}", conversationId);
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 规范化查询字符串
     */
    private String normalizeQuery(String query) {
        if (query == null) return "";
        return query.trim().toLowerCase()
            .replaceAll("\\s+", " ")
            .replaceAll("[\u3001\uff0c\u3002\uff01\uff1f\uff1b\uff1a\u201c\u201d\u2018\u2019\u3010\u3011\u300a\u300b\uff08\uff09]", "");
    }
    
    /**
     * 生成意图摘要
     */
    private String generateIntentSummary(UserIntent intent) {
        if (intent == null) return "";
        
        StringBuilder sb = new StringBuilder();
        if (intent.getKeywords() != null) {
            sb.append("kw:").append(String.join(",", intent.getKeywords()));
        }
        if (intent.getArtists() != null && !intent.getArtists().isEmpty()) {
            sb.append("|ar:").append(String.join(",", intent.getArtists()));
        }
        if (intent.getGenres() != null && !intent.getGenres().isEmpty()) {
            sb.append("|ge:").append(String.join(",", intent.getGenres()));
        }
        return sb.toString();
    }
    
    /**
     * MD5 哈希
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("[Cache] MD5 hash failed", e);
            return input;
        }
    }
}
