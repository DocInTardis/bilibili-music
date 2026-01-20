package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${REDIS_ENABLED:true}")
    private boolean redisEnabled;

    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean(false);
    
    // 缓存 TTL 配置（秒）
    private static final long QUERY_CACHE_TTL = 3600;        // 1小时
    private static final long KEYWORD_CACHE_TTL = 7200;      // 2小时
    private static final long LLM_RESULT_CACHE_TTL = 86400;  // 24小时
    private static final long PREFERENCE_CACHE_TTL = 604800; // 7天
    private static final long VIDEO_DETAIL_CACHE_TTL = 604800; // 7 days
    // 行为序列状态 TTL（与偏好保持一致）
    private static final long BEHAVIOR_SEQ_TTL = PREFERENCE_CACHE_TTL;
    // Prompt 结果缓存 TTL（复用 LLM 结果的 24 小时窗口）
    private static final long PROMPT_RESULT_CACHE_TTL = LLM_RESULT_CACHE_TTL;
    
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
        if (!isRedisUsable()) {
            return;
        }
        try {
            String key = generateQueryCacheKey(query);
            String json = objectMapper.writeValueAsString(videos);
            stringRedisTemplate.opsForValue().set(key, json, QUERY_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 缓存搜索结果: query={}, videos={}", query, videos.size());
        } catch (JsonProcessingException e) {
            log.warn("[Cache] 序列化搜索结果失败: {}", e.getMessage());
        } catch (Exception e) {
            markRedisFailed("cacheSearchResults", e);
        }
    }
    
    /**
     * 获取缓存的搜索结果
     */
    @SuppressWarnings("unchecked")
    public List<VideoInfo> getCachedSearchResults(String query) {
        if (!isRedisUsable()) {
            return null;
        }
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
            markRedisFailed("getCachedSearchResults", e);
        }
        return null;
    }
    
    /**
     * 缓存关键词提取结果
     */
    public void cacheKeywords(String query, List<String> keywords) {
        if (!isRedisUsable()) {
            return;
        }
        try {
            String key = "keywords:" + md5(normalizeQuery(query));
            String json = objectMapper.writeValueAsString(keywords);
            stringRedisTemplate.opsForValue().set(key, json, KEYWORD_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 缓存关键词: query={}, keywords={}", query, keywords);
        } catch (JsonProcessingException e) {
            log.warn("[Cache] 序列化关键词失败: {}", e.getMessage());
        } catch (Exception e) {
            markRedisFailed("cacheKeywords", e);
        }
    }
    
    /**
     * 获取缓存的关键词
     */
    @SuppressWarnings("unchecked")
    public List<String> getCachedKeywords(String query) {
        if (!isRedisUsable()) {
            return null;
        }
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
            markRedisFailed("getCachedKeywords", e);
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
     * 按视频粒度清空该视频的所有 LLM 判断缓存（用于智能失效）
     */
    public void evictLLMJudgementsForVideo(String bvid) {
        if (!isRedisUsable()) {
            return;
        }
        if (bvid == null || bvid.isBlank()) {
            return;
        }
        String pattern = "llm:judge:" + bvid + ":*";
        try {
            java.util.Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("[Cache] 智能失效：清理视频的LLM判断缓存 bvid={}, keys={}", bvid, keys.size());
            }
        } catch (Exception e) {
            markRedisFailed("evictLLMJudgementsForVideo", e);
        }
    }
        
    /**
     * 缓存 LLM 判断结果
     */
    public void cacheLLMJudgement(String bvid, UserIntent intent, VideoRelevanceScorer.ScoringResult result) {
        if (!isRedisUsable()) {
            return;
        }
        try {
            String key = generateLLMCacheKey(bvid, intent);
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(key, json, LLM_RESULT_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 缓存LLM判断: bvid={}, score={}", bvid, result.getScore());
        } catch (JsonProcessingException e) {
            log.warn("[Cache] 序列化LLM判断结果失败: {}", e.getMessage());
        } catch (Exception e) {
            markRedisFailed("cacheLLMJudgement", e);
        }
    }
    
    /**
     * 获取缓存的 LLM 判断结果
     */
    public VideoRelevanceScorer.ScoringResult getCachedLLMJudgement(String bvid, UserIntent intent) {
        if (!isRedisUsable()) {
            return null;
        }
        try {
            String key = generateLLMCacheKey(bvid, intent);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                VideoRelevanceScorer.ScoringResult result = objectMapper.readValue(json, VideoRelevanceScorer.ScoringResult.class);
                log.debug("[Cache] 命中LLM判断缓存: bvid={}, score={}", bvid, result.getScore());
                return result;
            }
        } catch (Exception e) {
            markRedisFailed("getCachedLLMJudgement", e);
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
        if (!isRedisUsable()) {
            return;
        }
        String key = getUserPreferenceKey(conversationId);
        String member = preferenceType + ":" + target.toLowerCase();
        
        // 使用 ZSet 的 incrementScore
        try {
            Double newScore = stringRedisTemplate.opsForZSet().incrementScore(key, member, deltaWeight);
            stringRedisTemplate.expire(key, PREFERENCE_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 增加用户偏好: conversationId={}, {}={}, newWeight={}", 
                conversationId, preferenceType, target, newScore);
        } catch (Exception e) {
            markRedisFailed("incrementPreference", e);
        }
    }
    
    /**
     * 获取艺人偏好权重（从 ZSet）
     */
    public Map<String, Integer> getArtistPreferences(Long conversationId) {
        if (!isRedisUsable()) {
            return Collections.emptyMap();
        }
        return getPreferencesByType(conversationId, "artist");
    }
    
    /**
     * 获取关键词偏好权重（从 ZSet）
     */
    public Map<String, Integer> getKeywordPreferences(Long conversationId) {
        if (!isRedisUsable()) {
            return Collections.emptyMap();
        }
        return getPreferencesByType(conversationId, "keyword");
    }
    
    /**
     * 根据类型获取偏好权重
     */
    private Map<String, Integer> getPreferencesByType(Long conversationId, String type) {
        if (!isRedisUsable()) {
            return Collections.emptyMap();
        }
        String key = getUserPreferenceKey(conversationId);
        
        // 获取所有成员和分数
        Set<ZSetOperations.TypedTuple<String>> tuples;
        try {
            tuples = stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
        } catch (Exception e) {
            markRedisFailed("getPreferencesByType", e);
            return Collections.emptyMap();
        }
        
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, Integer> result = new HashMap<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
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
        if (!isRedisUsable()) {
            return Collections.emptyMap();
        }
        String key = getUserPreferenceKey(conversationId);
        
        // 按分数倒序获取 Top N
        Set<ZSetOperations.TypedTuple<String>> tuples;
        try {
            tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(key, 0, topN - 1);
        } catch (Exception e) {
            markRedisFailed("getTopPreferences", e);
            return Collections.emptyMap();
        }
        
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyMap();
        }
        
        return tuples.stream()
            .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
            .collect(Collectors.toMap(
                ZSetOperations.TypedTuple::getValue,
                tuple -> tuple.getScore().intValue(),
                (a, b) -> a,
                LinkedHashMap::new
            ));
    }
    
    /**
     * 清除用户偏好缓存
     */
    public void clearUserPreference(Long conversationId) {
        if (!isRedisUsable()) {
            return;
        }
        String key = getUserPreferenceKey(conversationId);
        try {
            stringRedisTemplate.delete(key);
            log.debug("[Cache] 清除用户偏好缓存: conversationId={}", conversationId);
        } catch (Exception e) {
            markRedisFailed("clearUserPreference", e);
        }
    }
    
    // ==================== 4. 行为序列特征缓存 ====================
    
    /**
     * 更新连续负向行为计数（例如连续跳过同一艺人）
     */
    public void updateConsecutiveNegativeCount(Long conversationId, String targetType, String targetId, boolean negative) {
        if (!isRedisUsable()) {
            return;
        }
        if (conversationId == null || targetType == null || targetId == null) {
            return;
        }
        String key = buildConsecutiveNegativeKey(conversationId, targetType, targetId);
        try {
            if (negative) {
                stringRedisTemplate.opsForValue().increment(key);
            } else {
                stringRedisTemplate.opsForValue().set(key, "0");
            }
            stringRedisTemplate.expire(key, BEHAVIOR_SEQ_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            markRedisFailed("updateConsecutiveNegativeCount", e);
        }
    }
    
    /**
     * 获取连续负向行为计数
     */
    public int getConsecutiveNegativeCount(Long conversationId, String targetType, String targetId) {
        if (!isRedisUsable()) {
            return 0;
        }
        if (conversationId == null || targetType == null || targetId == null) {
            return 0;
        }
        String key = buildConsecutiveNegativeKey(conversationId, targetType, targetId);
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null) {
                return 0;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            markRedisFailed("getConsecutiveNegativeCount", e);
            return 0;
        }
    }
    
    private String buildConsecutiveNegativeKey(Long conversationId, String targetType, String targetId) {
        return "behavior:seq:neg:" + conversationId + ":" + targetType + ":" + targetId;
    }
    
    // ==================== 5. Prompt 执行结果缓存 ====================
    
    /**
     * 构建 Prompt 结果缓存 Key。
     * 一般由 nodeName + promptVersion + 输入内容 组合后取 MD5。
     */
    public String buildPromptCacheKey(String nodeName, String version, String input) {
        StringBuilder sb = new StringBuilder();
        sb.append(nodeName != null ? nodeName : "unknown")
          .append(":")
          .append(version != null ? version : "v1")
          .append(":")
          .append(input != null ? input : "");
        return "llm:prompt:" + md5(sb.toString());
    }
    
    /**
     * 缓存 Prompt 执行结果（例如总结文案、结构化 JSON 文本等）。
     */
    public void cachePromptResult(String cacheKey, String result) {
        if (!isRedisUsable()) {
            return;
        }
        if (cacheKey == null || cacheKey.isBlank() || result == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, result, PROMPT_RESULT_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] 缓存 Prompt 结果: key={}", cacheKey);
        } catch (Exception e) {
            markRedisFailed("cachePromptResult", e);
        }
    }
    
    /**
     * 获取缓存的 Prompt 执行结果。
     */
    public String getCachedPromptResult(String cacheKey) {
        if (!isRedisUsable()) {
            return null;
        }
        if (cacheKey == null || cacheKey.isBlank()) {
            return null;
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(cacheKey);
            if (value != null) {
                log.debug("[Cache] 命中 Prompt 结果缓存: key={}", cacheKey);
            }
            return value;
        } catch (Exception e) {
            markRedisFailed("getCachedPromptResult", e);
            return null;
        }
    }

    // ==================== 6. Video detail cache ====================

    public String buildVideoDetailCacheKey(String bvid, String url) {
        if (bvid != null && !bvid.isBlank()) {
            return "video:detail:bvid:" + bvid.trim();
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        return "video:detail:url:" + md5(url.trim());
    }

    public void cacheVideoDetail(VideoInfo video) {
        if (!isRedisUsable()) {
            return;
        }
        if (video == null) {
            return;
        }
        String key = buildVideoDetailCacheKey(video.getBvid(), video.getUrl());
        if (key == null) {
            return;
        }
        try {
            VideoInfo snapshot = VideoInfo.builder()
                .bvid(video.getBvid())
                .title(video.getTitle())
                .tags(video.getTags())
                .description(video.getDescription())
                .playCount(video.getPlayCount())
                .commentCount(video.getCommentCount())
                .build();
            String json = objectMapper.writeValueAsString(snapshot);
            stringRedisTemplate.opsForValue().set(key, json, VIDEO_DETAIL_CACHE_TTL, TimeUnit.SECONDS);
            log.debug("[Cache] Cache video detail: key={}, bvid={}", key, video.getBvid());
        } catch (Exception e) {
            markRedisFailed("cacheVideoDetail", e);
        }
    }

    public VideoInfo getCachedVideoDetail(String bvid, String url) {
        if (!isRedisUsable()) {
            return null;
        }
        String key = buildVideoDetailCacheKey(bvid, url);
        if (key == null) {
            return null;
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            VideoInfo cached = objectMapper.readValue(json, VideoInfo.class);
            log.debug("[Cache] Hit video detail cache: key={}, bvid={}", key, bvid);
            return cached;
        } catch (Exception e) {
            markRedisFailed("getCachedVideoDetail", e);
            return null;
        }
    }
    
    private boolean isRedisUsable() {
        return redisEnabled && redisAvailable.get();
    }

    private void markRedisFailed(String op, Exception e) {
        redisAvailable.set(false);
        if (redisFailureLogged.compareAndSet(false, true)) {
            log.warn("[Cache] Redis unavailable, disable cache. op={}, reason={}", op,
                e != null ? e.getMessage() : "unknown");
        } else {
            log.debug("[Cache] op={} failed: {}", op, e != null ? e.getMessage() : "unknown");
        }
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
