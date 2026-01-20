package com.example.bilibilimusic.context;

import com.example.bilibilimusic.dto.MusicUnit;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class WorkingMemory {
    private List<String> keywords = new ArrayList<>();
    private List<VideoInfo> searchResults = new ArrayList<>();
    private List<MusicUnit> musicUnits = new ArrayList<>();
    private List<VideoInfo> selectedVideos = new ArrayList<>();
    private List<VideoInfo> trashVideos = new ArrayList<>();
    private List<VideoInfo> rejectedVideos = new ArrayList<>();
    private String summary;
    private String selectionReason;
    private Map<String, Integer> recallChannelCounts = new HashMap<>();
    private List<String> recallQueries = new ArrayList<>();
}
