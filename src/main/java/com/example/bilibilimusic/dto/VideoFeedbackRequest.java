package com.example.bilibilimusic.dto;

import lombok.Data;

@Data
public class VideoFeedbackRequest {
    private Long conversationId;
    private String bvid;
    private String title;
    private String author;
    private String url;
    private String comment;
}
