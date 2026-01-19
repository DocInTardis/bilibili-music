package com.example.bilibilimusic.service.job;

import com.example.bilibilimusic.agent.PlaylistAgent;
import com.example.bilibilimusic.dto.ChatMessage;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.service.job.PlaylistJobQueueService.Snapshot;
import com.example.bilibilimusic.service.job.PlaylistJobQueueService.Status;
import com.example.bilibilimusic.service.websocket.WsTopicPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class PlaylistJobWorker {

    private final PlaylistJobQueueService jobQueueService;
    private final PlaylistAgent playlistAgent;
    private final WsTopicPublisher wsTopicPublisher;
    private final Executor executor;

    public PlaylistJobWorker(PlaylistJobQueueService jobQueueService,
                             PlaylistAgent playlistAgent,
                             WsTopicPublisher wsTopicPublisher,
                             @Qualifier("jobQueueExecutor") Executor executor) {
        this.jobQueueService = jobQueueService;
        this.playlistAgent = playlistAgent;
        this.wsTopicPublisher = wsTopicPublisher;
        this.executor = executor;
    }

    @Value("${job.queue.enabled:true}")
    private boolean enabled;

    @Value("${job.queue.batch-size:4}")
    private int batchSize;

    @Value("${job.queue.processing-timeout-ms:600000}")
    private long processingTimeoutMs;

    @Scheduled(fixedDelayString = "${job.queue.poll-interval-ms:250}")
    public void pollAndDispatch() {
        if (!enabled || !jobQueueService.isEnabled()) {
            return;
        }
        for (int i = 0; i < Math.max(1, batchSize); i++) {
            String jobId = jobQueueService.popToProcessing();
            if (jobId == null || jobId.isBlank()) {
                return;
            }
            executor.execute(() -> process(jobId));
        }
    }

    @Scheduled(fixedDelayString = "${job.queue.processing-timeout-ms:600000}")
    public void recoverStuckProcessing() {
        if (!enabled || !jobQueueService.isEnabled()) {
            return;
        }
        try {
            List<String> ids = jobQueueService.listProcessing(50);
            if (ids == null || ids.isEmpty()) {
                return;
            }
            long now = Instant.now().toEpochMilli();
            for (String jobId : ids) {
                Snapshot snapshot = jobQueueService.get(jobId);
                if (snapshot == null) {
                    jobQueueService.ackProcessing(jobId);
                    continue;
                }
                if (!Status.RUNNING.name().equals(snapshot.status())) {
                    continue;
                }
                Long startedAt = snapshot.startedAt();
                if (startedAt != null && now - startedAt > processingTimeoutMs) {
                    log.warn("[JobQueue] requeue stuck job: id={}, startedAt={}", jobId, startedAt);
                    jobQueueService.requeue(jobId);
                }
            }
        } catch (Exception e) {
            log.debug("[JobQueue] recovery failed: {}", e.getMessage());
        }
    }

    private void process(String jobId) {
        if (!jobQueueService.tryAcquireLock(jobId)) {
            jobQueueService.ackProcessing(jobId);
            return;
        }
        try {
            Snapshot snapshot = jobQueueService.get(jobId);
            if (snapshot == null || snapshot.request() == null) {
                jobQueueService.ackProcessing(jobId);
                return;
            }
            if (!Status.PENDING.name().equals(snapshot.status()) && !Status.FAILED.name().equals(snapshot.status())) {
                jobQueueService.ackProcessing(jobId);
                return;
            }

            int attempt = snapshot.attempts() + 1;
            jobQueueService.markRunning(jobId, attempt);

            PlaylistRequest request = snapshot.request();
            publishStatus(jobId, "任务开始执行 (attempt " + attempt + ")");

            PlaylistResponse response = playlistAgent.execute(request, status -> publishStatus(jobId, status));

            jobQueueService.markSucceeded(jobId, response);
            jobQueueService.ackProcessing(jobId);
            publishResult(jobId, response);
        } catch (Exception e) {
            Snapshot snapshot = jobQueueService.get(jobId);
            int attempt = snapshot != null ? snapshot.attempts() : 1;
            int maxAttempts = snapshot != null ? snapshot.maxAttempts() : 1;
            boolean dead = attempt >= maxAttempts;

            jobQueueService.markFailed(jobId, e.getMessage(), dead);
            if (dead) {
                jobQueueService.toDlq(jobId);
                publishError(jobId, "任务失败已进入 DLQ: " + e.getMessage());
            } else {
                jobQueueService.requeue(jobId);
                publishError(jobId, "任务失败将重试: " + e.getMessage());
            }
        } finally {
            jobQueueService.releaseLock(jobId);
        }
    }

    private void publishStatus(String jobId, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("jobId", jobId);
        payload.put("status", status);
        ChatMessage msg = ChatMessage.builder()
            .type("status")
            .content(status)
            .payload(payload)
            .build();
        wsTopicPublisher.send("/topic/messages", msg);
    }

    private void publishResult(String jobId, PlaylistResponse response) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("jobId", jobId);
        ChatMessage msg = ChatMessage.builder()
            .type("result")
            .summary(response.getSummary())
            .videos(response.getVideos())
            .trashVideos(response.getTrashVideos())
            .payload(payload)
            .build();
        wsTopicPublisher.send("/topic/messages", msg);
    }

    private void publishError(String jobId, String error) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("jobId", jobId);
        ChatMessage msg = ChatMessage.builder()
            .type("error")
            .content(error)
            .payload(payload)
            .build();
        wsTopicPublisher.send("/topic/messages", msg);
    }
}
