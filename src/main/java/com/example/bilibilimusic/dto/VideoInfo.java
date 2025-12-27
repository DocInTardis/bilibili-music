package com.example.bilibilimusic.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoInfo {
    private String title;
    private String url;
    private String author;
    private String duration;
}
