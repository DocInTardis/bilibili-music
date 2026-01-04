package com.example.bilibilimusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * LLM 预算控制服务：按会话/用户/时间窗口限制调用次数
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmBudgetService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final long CONVERSATION_WINDOW_SECONDS = 3600;   // 会话级窗口：1小时
    private static final int  CONVERSATION_LIMIT        = 50;      // 每小时最多 50 次

    private static final long USER_WINDOW_SECONDS        = 86400;   // 用户级窗口：24小时
    private static final int  USER_LIMIT                 = 200;     // 每天最多 200 次

    private static final double NEAR_LIMIT_RATIO         = 0.8;     // 80% 视为接近耗尽

    public enum BudgetStatus {
        AVAILABLE,
        NEAR_LIMIT,
        EXCEEDED
    }

    /**
     * 检查并消耗一次 LLM 调用配额（会话 + 用户 双重维度）
     */
    public BudgetStatus checkAndConsume(Long conversationId, Long userId) {
        BudgetStatus convStatus = BudgetStatus.AVAILABLE;
        BudgetStatus userStatus = BudgetStatus.AVAILABLE;

        if (conversationId != null) {
            convStatus = checkAndConsumeForKey(buildConversationKey(conversationId), CONVERSATION_LIMIT, CONVERSATION_WINDOW_SECONDS);
        }
        if (userId != null) {
            userStatus = checkAndConsumeForKey(buildUserKey(userId), USER_LIMIT, USER_WINDOW_SECONDS);
        }

        if (convStatus == BudgetStatus.EXCEEDED || userStatus == BudgetStatus.EXCEEDED) {
            return BudgetStatus.EXCEEDED;
        }
        if (convStatus == BudgetStatus.NEAR_LIMIT || userStatus == BudgetStatus.NEAR_LIMIT) {
            return BudgetStatus.NEAR_LIMIT;
        }
        return BudgetStatus.AVAILABLE;
    }

    private BudgetStatus checkAndConsumeForKey(String key, int limit, long windowSeconds) {
        try {
            Long current = stringRedisTemplate.opsForValue().increment(key);
            if (current == null) {
                return BudgetStatus.AVAILABLE;
            }
            if (current == 1L) {
                stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            if (current > limit) {
                log.debug("[LlmBudget] 超出配额: key={}, current={}, limit={}", key, current, limit);
                return BudgetStatus.EXCEEDED;
            }
            if (current >= (long) (limit * NEAR_LIMIT_RATIO)) {
                log.debug("[LlmBudget] 接近配额上限: key={}, current={}, limit={}", key, current, limit);
                return BudgetStatus.NEAR_LIMIT;
            }
            return BudgetStatus.AVAILABLE;
        } catch (Exception e) {
            log.warn("[LlmBudget] 配额检查失败，默认放行: key={}", key, e);
            return BudgetStatus.AVAILABLE;
        }
    }

    private String buildConversationKey(Long conversationId) {
        return "llm:quota:conv:" + conversationId;
    }

    private String buildUserKey(Long userId) {
        return "llm:quota:user:" + userId;
    }
}
