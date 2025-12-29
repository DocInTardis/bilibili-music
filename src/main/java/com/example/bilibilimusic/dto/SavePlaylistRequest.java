package com.example.bilibilimusic.dto;

import lombok.Data;

/**
 * 保存播放列表请求
 */
@Data
public class SavePlaylistRequest {
    private Long playlistId;
    private String name;
}
