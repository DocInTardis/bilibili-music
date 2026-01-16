package com.example.bilibilimusic.service.job;

import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.util.HashUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlaylistJobQueueService {

    private static final String PREFIX = "job:playlist:";
    private static final String Q_PENDING = "queue:playlist:pending";
    private static final String Q_PROCESSING = "queue:playlist:processing";
    private static final String Q_DLQ = "queue:playlist:dlq";
    private static final String DEDUP_PREFIX = "dedup:playlist:";
    private static final String LOCK_PREFIX = "lock:playlist:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${job.queue.enabled:true}")
    private boolean enabled;

    @Value("${job.queue.max-attempts:3}")
    private int maxAttempts;

    @Value("${job.queue.processing-timeout-ms:600000}")
    private long processingTimeoutMs;

    @Value("${job.queue.dedup-ttl-ms:86400000}")
    private long dedupTtlMs;

    public boolean isEnabled() {
        return enabled;
    }

    public String submit(PlaylistRequest request) {
        if (!enabled) {
            throw new IllegalStateException("job queue disabled");
        }
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String dedupKey = HashUtil.md5(requestJson);
            String dedupRedisKey = DEDUP_PREFIX + dedupKey;

            String existing = redis.opsForValue().get(dedupRedisKey);
            if (existing != null && !existing.isBlank()) {
                return existing;
            }

            String jobId = UUID.randomUUID().toString();
            boolean ok = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                dedupRedisKey, jobId, dedupTtlMs, TimeUnit.MILLISECONDS));
            if (!ok) {
                String again = redis.opsForValue().get(dedupRedisKey);
                if (again != null && !again.isBlank()) {
                    return again;
                }
            }

            long now = Instant.now().toEpochMilli();
            Map<String, String> data = new HashMap<>();
            data.put("id", jobId);
            data.put("status", Status.PENDING.name());
            data.put("createdAt", String.valueOf(now));
            data.put("updatedAt", String.valueOf(now));
            data.put("attempts", "0");
            data.put("maxAttempts", String.valueOf(Math.max(1, maxAttempts)));
            data.put("requestJson", requestJson);
            redis.opsForHash().putAll(jobKey(jobId), data);

            redis.opsForList().leftPush(Q_PENDING, jobId);
            return jobId;
        } catch (Exception e) {
            throw new RuntimeException("submit job failed", e);
        }
    }

    public Snapshot get(String jobId) {
        Map<Object, Object> map = redis.opsForHash().entries(jobKey(jobId));
        if (map == null || map.isEmpty()) {
            return null;
        }
        return Snapshot.from(map);
    }

    public boolean tryAcquireLock(String jobId) {
        String key = LOCK_PREFIX + jobId;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
            key, "1", processingTimeoutMs, TimeUnit.MILLISECONDS));
    }

    public void releaseLock(String jobId) {
        redis.delete(LOCK_PREFIX + jobId);
    }

    public void markRunning(String jobId, int attempt) {
        long now = Instant.now().toEpochMilli();
        redis.opsForHash().put(jobKey(jobId), "status", Status.RUNNING.name());
        redis.opsForHash().put(jobKey(jobId), "updatedAt", String.valueOf(now));
        redis.opsForHash().put(jobKey(jobId), "startedAt", String.valueOf(now));
        redis.opsForHash().put(jobKey(jobId), "attempts", String.valueOf(attempt));
        redis.opsForHash().delete(jobKey(jobId), "error");
    }

    public void markSucceeded(String jobId, PlaylistResponse response) {
        try {
            long now = Instant.now().toEpochMilli();
            redis.opsForHash().put(jobKey(jobId), "status", Status.SUCCEEDED.name());
            redis.opsForHash().put(jobKey(jobId), "updatedAt", String.valueOf(now));
            redis.opsForHash().put(jobKey(jobId), "finishedAt", String.valueOf(now));
            redis.opsForHash().put(jobKey(jobId), "resultJson", objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new RuntimeException("mark success failed", e);
        }
    }

    public void markFailed(String jobId, String error, boolean deadLetter) {
        long now = Instant.now().toEpochMilli();
        redis.opsForHash().put(jobKey(jobId), "status", (deadLetter ? Status.DEAD : Status.FAILED).name());
        redis.opsForHash().put(jobKey(jobId), "updatedAt", String.valueOf(now));
        redis.opsForHash().put(jobKey(jobId), "finishedAt", String.valueOf(now));
        if (error != null) {
            redis.opsForHash().put(jobKey(jobId), "error", error);
        }
    }

    public void ackProcessing(String jobId) {
        redis.opsForList().remove(Q_PROCESSING, 1, jobId);
    }

    public String popToProcessing() {
        return redis.opsForList().rightPopAndLeftPush(Q_PENDING, Q_PROCESSING);
    }

    public void requeue(String jobId) {
        redis.opsForList().remove(Q_PROCESSING, 1, jobId);
        redis.opsForList().leftPush(Q_PENDING, jobId);
        long now = Instant.now().toEpochMilli();
        redis.opsForHash().put(jobKey(jobId), "status", Status.PENDING.name());
        redis.opsForHash().put(jobKey(jobId), "updatedAt", String.valueOf(now));
    }

    public void toDlq(String jobId) {
        redis.opsForList().remove(Q_PROCESSING, 1, jobId);
        redis.opsForList().leftPush(Q_DLQ, jobId);
    }

    public void retryFromDlq(String jobId) {
        redis.opsForList().remove(Q_DLQ, 1, jobId);
        redis.opsForHash().put(jobKey(jobId), "status", Status.PENDING.name());
        redis.opsForHash().put(jobKey(jobId), "updatedAt", String.valueOf(Instant.now().toEpochMilli()));
        redis.opsForHash().put(jobKey(jobId), "attempts", "0");
        redis.opsForHash().delete(jobKey(jobId), "error");
        redis.opsForHash().delete(jobKey(jobId), "resultJson");
        redis.opsForList().leftPush(Q_PENDING, jobId);
    }

    public String getProcessingQueueKey() {
        return Q_PROCESSING;
    }

    public List<String> listProcessing(int max) {
        return redis.opsForList().range(Q_PROCESSING, 0, Math.max(0, max - 1));
    }

    public String jobKey(String jobId) {
        return PREFIX + jobId;
    }

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        DEAD
    }

    public record Snapshot(String id,
                           String status,
                           int attempts,
                           int maxAttempts,
                           long createdAt,
                           Long startedAt,
                           Long finishedAt,
                           String error,
                           PlaylistRequest request,
                           PlaylistResponse result) {

        static Snapshot from(Map<Object, Object> map) {
            String id = str(map.get("id"));
            String status = str(map.get("status"));
            int attempts = intVal(map.get("attempts"));
            int maxAttempts = intVal(map.get("maxAttempts"));
            long createdAt = longVal(map.get("createdAt"), 0L);
            Long startedAt = optLong(map.get("startedAt"));
            Long finishedAt = optLong(map.get("finishedAt"));
            String error = str(map.get("error"));
            String requestJson = str(map.get("requestJson"));
            String resultJson = str(map.get("resultJson"));

            PlaylistRequest request = null;
            PlaylistResponse result = null;
            try {
                ObjectMapper mapper = new ObjectMapper();
                if (requestJson != null && !requestJson.isBlank()) {
                    request = mapper.readValue(requestJson, PlaylistRequest.class);
                }
                if (resultJson != null && !resultJson.isBlank()) {
                    result = mapper.readValue(resultJson, PlaylistResponse.class);
                }
            } catch (Exception ignored) {
            }

            return new Snapshot(id, status, attempts, maxAttempts, createdAt, startedAt, finishedAt, error, request, result);
        }

        private static String str(Object o) {
            return o != null ? String.valueOf(o) : null;
        }

        private static int intVal(Object o) {
            try {
                return o != null ? Integer.parseInt(String.valueOf(o)) : 0;
            } catch (Exception e) {
                return 0;
            }
        }

        private static long longVal(Object o, long def) {
            try {
                return o != null ? Long.parseLong(String.valueOf(o)) : def;
            } catch (Exception e) {
                return def;
            }
        }

        private static Long optLong(Object o) {
            if (o == null) {
                return null;
            }
            try {
                return Long.parseLong(String.valueOf(o));
            } catch (Exception e) {
                return null;
            }
        }
    }
}
