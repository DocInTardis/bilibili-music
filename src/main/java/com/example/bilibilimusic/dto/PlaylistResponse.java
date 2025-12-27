package com.example.bilibilimusic.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PlaylistResponse {

    /**
     * 通过 Playwright 从 B 站抓取的原始视频列表。
     */
    private List<VideoInfo> videos;

    /**
     * 调用本地 Ollama (qwen:7b) 后生成的歌单总结 / 推荐说明。
     */
    private String summary;

    /**
     * 如果未来实现 MP3 下载，可以返回本地文件路径列表等信息（目前预留）。
     */
    private List<String> mp3Files;
}
