package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.service.PlaylistAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/playlist")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistAgentService playlistAgentService;

    @PostMapping
    public ResponseEntity<PlaylistResponse> generate(@Valid @RequestBody PlaylistRequest request) {
        PlaylistResponse response = playlistAgentService.generatePlaylist(request);
        return ResponseEntity.ok(response);
    }
}
