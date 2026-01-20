package com.example.bilibilimusic.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class VideoInfo {
    private String bvid;
    private String title;
    private String url;
    private String author;
    private String duration;
    private String tags;
    private String description;
    private Long playCount;
    private Long commentCount;
    private String coverUrl;

    private String albumTitle;
    private Integer trackNo;

    private List<String> recallSources;
    private Double rerankScore;
    private Map<String, Double> rerankBreakdown;
    private String rerankReason;
}
