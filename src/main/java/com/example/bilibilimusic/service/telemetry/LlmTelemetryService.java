package com.example.bilibilimusic.service.telemetry;

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
public class LlmTelemetryService {

    private static final long TTL_DAYS = 14;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public void recordChatCall(String nodeName,
                               String promptVersion,
                               String model,
                               boolean cacheHit,
                               boolean success,
                               long durationMs,
                               Integer promptTokens,
                               Integer completionTokens) {
        String safeNode = nodeName != null ? nodeName : "unknown";
        String safeVer = promptVersion != null ? promptVersion : "v?";
        String safeModel = model != null ? model : "unknown";

        sadd("agent:llm:chat:nodes", safeNode);
        sadd("agent:llm:chat:node:" + safeNode + ":versions", safeVer);

        incr("agent:llm:chat:total");
        incr("agent:llm:chat:node:" + safeNode + ":total");
        incr("agent:llm:chat:node:" + safeNode + ":v:" + safeVer + ":total");

        if (success) {
            incr("agent:llm:chat:node:" + safeNode + ":success");
            incr("agent:llm:chat:node:" + safeNode + ":v:" + safeVer + ":success");
        }
        if (cacheHit) {
            incr("agent:llm:chat:node:" + safeNode + ":cache_hit");
            incr("agent:llm:chat:node:" + safeNode + ":v:" + safeVer + ":cache_hit");
        }

        add("agent:llm:chat:node:" + safeNode + ":duration_sum", durationMs);
        incr("agent:llm:chat:node:" + safeNode + ":duration_count");
        add("agent:llm:chat:node:" + safeNode + ":v:" + safeVer + ":duration_sum", durationMs);
        incr("agent:llm:chat:node:" + safeNode + ":v:" + safeVer + ":duration_count");

        if (promptTokens != null) {
            add("agent:llm:chat:node:" + safeNode + ":prompt_tokens_sum", promptTokens);
            add("agent:llm:chat:node:" + safeNode + ":v:" + safeVer + ":prompt_tokens_sum", promptTokens);
        }
        if (completionTokens != null) {
            add("agent:llm:chat:node:" + safeNode + ":completion_tokens_sum", completionTokens);
            add("agent:llm:chat:node:" + safeNode + ":v:" + safeVer + ":completion_tokens_sum", completionTokens);
        }

        // 记录最近一次调用（便于排错）
        try {
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("node", safeNode);
            last.put("version", safeVer);
            last.put("model", safeModel);
            last.put("cacheHit", cacheHit);
            last.put("success", success);
            last.put("durationMs", durationMs);
            last.put("promptTokens", promptTokens);
            last.put("completionTokens", completionTokens);
            AgentTraceContext.Context ctx = AgentTraceContext.get();
            if (ctx != null) {
                last.put("playlistId", ctx.playlistId());
                last.put("conversationId", ctx.conversationId());
                last.put("executionId", ctx.executionId());
                last.put("graphNode", ctx.nodeName());
            }
            String json = objectMapper.writeValueAsString(last);
            set("agent:llm:chat:last", json);
        } catch (Exception e) {
            log.debug("[LlmTelemetry] serialize last call failed: {}", e.getMessage());
        }
    }

    public Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.totalCalls = parseLong(get("agent:llm:chat:total"));
        snapshot.lastCallJson = get("agent:llm:chat:last");

        Set<String> nodes = members("agent:llm:chat:nodes");
        if (nodes == null) {
            nodes = Collections.emptySet();
        }

        for (String node : nodes) {
            Snapshot.NodeStats nodeStats = new Snapshot.NodeStats();
            nodeStats.node = node;
            nodeStats.total = parseLong(get("agent:llm:chat:node:" + node + ":total"));
            nodeStats.success = parseLong(get("agent:llm:chat:node:" + node + ":success"));
            nodeStats.cacheHit = parseLong(get("agent:llm:chat:node:" + node + ":cache_hit"));
            long durSum = parseLong(get("agent:llm:chat:node:" + node + ":duration_sum"));
            long durCnt = parseLong(get("agent:llm:chat:node:" + node + ":duration_count"));
            nodeStats.avgDurationMs = durCnt > 0 ? (double) durSum / durCnt : 0.0;

            Set<String> versions = members("agent:llm:chat:node:" + node + ":versions");
            if (versions != null) {
                for (String ver : versions) {
                    Snapshot.VersionStats vs = new Snapshot.VersionStats();
                    vs.version = ver;
                    vs.total = parseLong(get("agent:llm:chat:node:" + node + ":v:" + ver + ":total"));
                    vs.success = parseLong(get("agent:llm:chat:node:" + node + ":v:" + ver + ":success"));
                    vs.cacheHit = parseLong(get("agent:llm:chat:node:" + node + ":v:" + ver + ":cache_hit"));
                    long vDurSum = parseLong(get("agent:llm:chat:node:" + node + ":v:" + ver + ":duration_sum"));
                    long vDurCnt = parseLong(get("agent:llm:chat:node:" + node + ":v:" + ver + ":duration_count"));
                    vs.avgDurationMs = vDurCnt > 0 ? (double) vDurSum / vDurCnt : 0.0;
                    nodeStats.versions.add(vs);
                }
            }
            nodeStats.versions.sort(Comparator.comparing(a -> a.version));
            snapshot.nodes.add(nodeStats);
        }
        snapshot.nodes.sort(Comparator.comparing(a -> a.node));
        return snapshot;
    }

    private void incr(String key) {
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, TTL_DAYS, TimeUnit.DAYS);
    }

    private void add(String key, long delta) {
        stringRedisTemplate.opsForValue().increment(key, delta);
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

    private Set<String> members(String key) {
        return stringRedisTemplate.opsForSet().members(key);
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
        private long totalCalls;
        private String lastCallJson;
        private List<NodeStats> nodes = new ArrayList<>();

        @Data
        public static class NodeStats {
            private String node;
            private long total;
            private long success;
            private long cacheHit;
            private double avgDurationMs;
            private List<VersionStats> versions = new ArrayList<>();
        }

        @Data
        public static class VersionStats {
            private String version;
            private long total;
            private long success;
            private long cacheHit;
            private double avgDurationMs;
        }
    }
}

