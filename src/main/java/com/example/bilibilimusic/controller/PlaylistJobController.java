package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.service.job.PlaylistJobQueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/playlist/jobs")
@RequiredArgsConstructor
public class PlaylistJobController {

    private final PlaylistJobQueueService jobQueueService;

    @PostMapping
    public ResponseEntity<SubmitResponse> submit(@Valid @RequestBody PlaylistRequest request) {
        String jobId = jobQueueService.submit(request);
        return ResponseEntity.ok(new SubmitResponse(jobId));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<PlaylistJobQueueService.Snapshot> get(@PathVariable String jobId) {
        PlaylistJobQueueService.Snapshot snapshot = jobQueueService.get(jobId);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/{jobId}/retry")
    public ResponseEntity<Void> retry(@PathVariable String jobId) {
        jobQueueService.retryFromDlq(jobId);
        return ResponseEntity.ok().build();
    }

    public record SubmitResponse(String jobId) {
    }
}

