package com.example.bilibilimusic.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class VideoFeedbackResponse {
    private String reply;
    private String sentiment;
    private Double intensity;
    private List<String> artists;
    private List<String> keywords;
    private Map<String, Integer> appliedArtists;
    private Map<String, Integer> appliedKeywords;
}
