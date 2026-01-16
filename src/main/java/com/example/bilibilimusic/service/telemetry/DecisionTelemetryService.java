package com.example.bilibilimusic.service.telemetry;

import com.example.bilibilimusic.entity.UserBehaviorEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class DecisionTelemetryService {

    private static final long TTL_DAYS = 14;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public enum Source {
        RULE,
        SCORE,
        LLM,
        HYBRID
    }

    public void recordDecision(Long conversationId,
                               String targetType,
                               String targetId,
                               Source source,
                               Boolean accepted,
                               Integer score,
                               String reasonCategory,
                               String promptVersion) {
        if (conversationId == null || targetType == null || targetId == null) {
            return;
        }
        String safeType = targetType.toLowerCase(Locale.ROOT);
        String safeId = targetId;
        String safeSource = source != null ? source.name() : Source.RULE.name();
        String safeReason = reasonCategory != null ? reasonCategory : "UNKNOWN";
        String safeVersion = promptVersion != null ? promptVersion : "v?";

        sadd("agent:decision:sources", safeSource);
        incr("agent:decision:total");
        incr("agent:decision:source:" + safeSource + ":total");
        incr("agent:decision:source:" + safeSource + ":reason:" + safeReason + ":total");

        if (Boolean.TRUE.equals(accepted)) {
            incr("agent:decision:source:" + safeSource + ":accepted");
        } else {
            incr("agent:decision:source:" + safeSource + ":rejected");
        }

        // 记录最后一次决策（用于后续在线评估）
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", conversationId);
            payload.put("targetType", safeType);
            payload.put("targetId", safeId);
            payload.put("source", safeSource);
            payload.put("accepted", accepted);
            payload.put("score", score);
            payload.put("reasonCategory", safeReason);
            payload.put("promptVersion", safeVersion);
            AgentTraceContext.Context ctx = AgentTraceContext.get();
            if (ctx != null) {
                payload.put("playlistId", ctx.playlistId());
                payload.put("executionId", ctx.executionId());
                payload.put("graphNode", ctx.nodeName());
            }
            String json = objectMapper.writeValueAsString(payload);
            set(lastDecisionKey(conversationId, safeType, safeId), json);
        } catch (Exception e) {
            log.debug("[DecisionTelemetry] save last decision failed: {}", e.getMessage());
        }
    }

    public void recordFeedback(UserBehaviorEvent event) {
        if (event == null || event.getConversationId() == null || event.getBehaviorType() == null) {
            return;
        }
        if (event.getTargetType() == null || event.getTargetId() == null) {
            return;
        }

        FeedbackLabel label = classifyFeedback(event);
        if (label == FeedbackLabel.NEUTRAL) {
            return;
        }

        String key = lastDecisionKey(event.getConversationId(), event.getTargetType().toLowerCase(Locale.ROOT), event.getTargetId());
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            Object sourceObj = payload.get("source");
            Object acceptedObj = payload.get("accepted");
            String source = sourceObj != null ? sourceObj.toString() : Source.RULE.name();
            boolean accepted = acceptedObj instanceof Boolean b && b;

            incr("agent:decision:feedback:total");
            incr("agent:decision:source:" + source + ":feedback:total");

            boolean correct = (label == FeedbackLabel.POSITIVE && accepted) || (label == FeedbackLabel.NEGATIVE && !accepted);
            if (correct) {
                incr("agent:decision:feedback:correct");
                incr("agent:decision:source:" + source + ":feedback:correct");
            } else {
                incr("agent:decision:feedback:wrong");
                incr("agent:decision:source:" + source + ":feedback:wrong");
            }
        } catch (Exception e) {
            log.debug("[DecisionTelemetry] parse last decision failed: {}", e.getMessage());
        }
    }

    public Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.totalDecisions = parseLong(get("agent:decision:total"));
        snapshot.feedbackTotal = parseLong(get("agent:decision:feedback:total"));
        snapshot.feedbackCorrect = parseLong(get("agent:decision:feedback:correct"));
        snapshot.feedbackWrong = parseLong(get("agent:decision:feedback:wrong"));
        snapshot.feedbackAccuracy = snapshot.feedbackTotal > 0
            ? (double) snapshot.feedbackCorrect / snapshot.feedbackTotal
            : 0.0;

        Set<String> sources = stringRedisTemplate.opsForSet().members("agent:decision:sources");
        if (sources != null) {
            for (String source : sources) {
                Snapshot.SourceStats s = new Snapshot.SourceStats();
                s.source = source;
                s.total = parseLong(get("agent:decision:source:" + source + ":total"));
                s.accepted = parseLong(get("agent:decision:source:" + source + ":accepted"));
                s.rejected = parseLong(get("agent:decision:source:" + source + ":rejected"));
                s.feedbackTotal = parseLong(get("agent:decision:source:" + source + ":feedback:total"));
                s.feedbackCorrect = parseLong(get("agent:decision:source:" + source + ":feedback:correct"));
                s.feedbackWrong = parseLong(get("agent:decision:source:" + source + ":feedback:wrong"));
                s.feedbackAccuracy = s.feedbackTotal > 0 ? (double) s.feedbackCorrect / s.feedbackTotal : 0.0;
                snapshot.sources.add(s);
            }
            snapshot.sources.sort(Comparator.comparing(a -> a.source));
        }
        return snapshot;
    }

    private FeedbackLabel classifyFeedback(UserBehaviorEvent event) {
        UserBehaviorEvent.BehaviorType type = event.getBehaviorType();
        if (type == null) {
            return FeedbackLabel.NEUTRAL;
        }
        if (type.isPositive()) {
            return FeedbackLabel.POSITIVE;
        }
        if (type.isNegative()) {
            return FeedbackLabel.NEGATIVE;
        }

        if (type == UserBehaviorEvent.BehaviorType.PLAY_PARTIAL) {
            double intensity = event.getIntensity() != null ? event.getIntensity() : 0.0;
            if (intensity >= 0.7) {
                return FeedbackLabel.POSITIVE;
            }
            if (intensity <= 0.2) {
                return FeedbackLabel.NEGATIVE;
            }
        }
        return FeedbackLabel.NEUTRAL;
    }

    private enum FeedbackLabel {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
    }

    private String lastDecisionKey(Long conversationId, String targetType, String targetId) {
        return "agent:decision:last:" + conversationId + ":" + targetType + ":" + targetId;
    }

    private void incr(String key) {
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, TTL_DAYS, TimeUnit.DAYS);
    }

    private void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value, TTL_DAYS, TimeUnit.DAYS);
    }

    private void sadd(String key, String member) {
        stringRedisTemplate.opsForSet().add(key, member);
        stringRedisTemplate.expire(key, TTL_DAYS, TimeUnit.DAYS);
    }

    private String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    private long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Data
    public static class Snapshot {
        private long totalDecisions;
        private long feedbackTotal;
        private long feedbackCorrect;
        private long feedbackWrong;
        private double feedbackAccuracy;
        private List<SourceStats> sources = new ArrayList<>();

        @Data
        public static class SourceStats {
            private String source;
            private long total;
            private long accepted;
            private long rejected;
            private long feedbackTotal;
            private long feedbackCorrect;
            private long feedbackWrong;
            private double feedbackAccuracy;
        }
    }
}

