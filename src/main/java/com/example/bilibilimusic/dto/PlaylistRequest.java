package com.example.bilibilimusic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class PlaylistRequest {

    /**
     * 用户自然语言需求，例如："来一份适合学习的纯音乐 B 站歌单"。
     */
    @NotBlank
    private String query;

    /**
     * 返回的视频数量上限。
     */
    @Positive
    private int limit = 10;

    /**
     * 额外偏好描述（可选），例如："偏日语 ACG，节奏不要太快"。
     */
    private String preference;

    /**
     * 是否期望下载为 MP3（目前仅预留，不真正下载）。
     */
    private boolean downloadAsMp3 = false;
}
