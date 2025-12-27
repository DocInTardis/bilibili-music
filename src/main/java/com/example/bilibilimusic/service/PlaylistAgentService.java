package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaylistAgentService {

    private final BilibiliSearchService bilibiliSearchService;
    private final OllamaService ollamaService;

    public PlaylistResponse generatePlaylist(PlaylistRequest request) {
        int limit = request.getLimit() > 0 ? request.getLimit() : 10;
        List<VideoInfo> videos = bilibiliSearchService.search(request.getQuery(), limit);
        if (videos.isEmpty()) {
            return PlaylistResponse.builder()
                    .videos(Collections.emptyList())
                    .summary("未从 B 站搜索到符合条件的视频，请尝试更换关键词。")
                    .mp3Files(Collections.emptyList())
                    .build();
        }

        String summary = ollamaService
                .summarizePlaylist(videos, request.getQuery(), request.getPreference())
                .blockOptional()
                .orElse("调用本地模型失败，暂时无法生成歌单总结，但已返回原始视频列表。");

        return PlaylistResponse.builder()
                .videos(videos)
                .summary(summary)
                .mp3Files(Collections.emptyList())
                .build();
    }
}
