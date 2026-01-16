package com.example.bilibilimusic.service.telemetry;

public final class AgentTraceContext {

    public record Context(Long playlistId, Long conversationId, String executionId, String nodeName) {}

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    private AgentTraceContext() {}

    public static void set(Context context) {
        CTX.set(context);
    }

    public static Context get() {
        return CTX.get();
    }

    public static void clear() {
        CTX.remove();
    }
}

