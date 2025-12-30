package com.example.bilibilimusic.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoInfo {
    private String bvid;  // B站视频BV号
    private String title;
    private String url;
    private String author;
    private String duration;
    private String tags;  // 新增：视频标签
    private String description;  // 新增：视频描述（可选）
    private Long playCount;  // 新增：播放量
    private Long commentCount;  // 新增：评论数
}
