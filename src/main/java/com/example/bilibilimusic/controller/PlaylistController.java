package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.agent.PlaylistAgent;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API 接口
 * 语义更清晰，但不破坏已有调用方
 */
@RestController
@RequestMapping("/api/playlist")
@RequiredArgsConstructor
@Slf4j
public class PlaylistController {

    private final PlaylistAgent playlistAgent;

    @PostMapping
    public ResponseEntity<PlaylistResponse> generate(@Valid @RequestBody PlaylistRequest request) {
        log.info("[REST API] 收到歌单生成请求：{}", request.getQuery());
        
        // 使用 Agent 执行任务（REST 接口不需要状态推送）
        PlaylistResponse response = playlistAgent.execute(request, status -> {
            log.debug("[REST API] 状态：{}", status);
        });
        
        return ResponseEntity.ok(response);
    }
}
