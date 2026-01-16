package com.example.bilibilimusic.service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AgentObservabilityMetrics {

    private final MeterRegistry meterRegistry;

    public void recordNodeExecution(String nodeName,
                                    String promptVersion,
                                    boolean success,
                                    int attempt,
                                    long durationMs) {
        if (nodeName == null || nodeName.isBlank()) {
            nodeName = "unknown";
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            promptVersion = "unknown";
        }

        Counter.builder("agent_node_executions_total")
            .tag("node", nodeName)
            .tag("prompt_version", promptVersion)
            .tag("success", Boolean.toString(success))
            .register(meterRegistry)
            .increment();

        if (attempt > 1) {
            Counter.builder("agent_node_retries_total")
                .tag("node", nodeName)
                .tag("prompt_version", promptVersion)
                .register(meterRegistry)
                .increment();
        }

        Timer.builder("agent_node_duration")
            .tag("node", nodeName)
            .tag("prompt_version", promptVersion)
            .tag("success", Boolean.toString(success))
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordLlmCall(String nodeName,
                              String promptVersion,
                              String model,
                              boolean cacheHit,
                              boolean success,
                              long durationMs) {
        if (nodeName == null || nodeName.isBlank()) {
            nodeName = "unknown";
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            promptVersion = "unknown";
        }
        if (model == null || model.isBlank()) {
            model = "unknown";
        }

        Counter.builder("agent_llm_calls_total")
            .tag("node", nodeName)
            .tag("prompt_version", promptVersion)
            .tag("model", model)
            .tag("cache_hit", Boolean.toString(cacheHit))
            .tag("success", Boolean.toString(success))
            .register(meterRegistry)
            .increment();

        Timer.builder("agent_llm_duration")
            .tag("node", nodeName)
            .tag("prompt_version", promptVersion)
            .tag("model", model)
            .tag("cache_hit", Boolean.toString(cacheHit))
            .tag("success", Boolean.toString(success))
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordWsSend(String destination, String messageType) {
        if (destination == null || destination.isBlank()) {
            destination = "unknown";
        }
        if (messageType == null || messageType.isBlank()) {
            messageType = "unknown";
        }
        Counter.builder("agent_ws_messages_total")
            .tag("destination", destination)
            .tag("type", messageType)
            .register(meterRegistry)
            .increment();
    }
}
