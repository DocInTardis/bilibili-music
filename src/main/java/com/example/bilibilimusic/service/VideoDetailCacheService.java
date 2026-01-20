package com.example.bilibilimusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.Video;
import com.example.bilibilimusic.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoDetailCacheService {

    @Value("${DB_ENABLED:true}")
    private boolean dbEnabled;

    @Value("${video.detail.refresh-hours:72}")
    private long refreshHours;

    private final VideoMapper videoMapper;

    private final AtomicBoolean dbAvailable = new AtomicBoolean(true);
    private final AtomicBoolean dbFailureLogged = new AtomicBoolean(false);

    public CacheEntry findDetail(String bvid, String url) {
        if (!isDbUsable()) {
            return null;
        }
        if ((bvid == null || bvid.isBlank()) && (url == null || url.isBlank())) {
            return null;
        }
        try {
            Video video = findVideo(bvid, url);
            if (video == null) {
                return null;
            }
            VideoInfo info = toVideoInfo(video);
            boolean stale = isStale(video);
            return new CacheEntry(info, stale, video.getCreatedAt());
        } catch (Exception e) {
            markDbFailed("findDetail", e);
            return null;
        }
    }

    public void upsertVideoDetail(VideoInfo videoInfo, boolean refreshAttempted) {
        if (!isDbUsable()) {
            return;
        }
        if (videoInfo == null) {
            return;
        }
        String bvid = extractBvid(videoInfo);
        String url = videoInfo.getUrl();
        if ((bvid == null || bvid.isBlank()) && (url == null || url.isBlank())) {
            return;
        }
        try {
            Video existing = findVideo(bvid, url);
            LocalDateTime now = LocalDateTime.now();
            boolean hasDetail = hasDetail(videoInfo);

            if (existing == null) {
                Video created = new Video();
                created.setPlatform("bilibili");
                created.setPlatformVid(bvid != null ? bvid : "");
                created.setCreatedAt(now);
                applyFields(created, videoInfo);
                videoMapper.insert(created);
                return;
            }

            applyFields(existing, videoInfo);
            if (refreshAttempted && hasDetail) {
                existing.setCreatedAt(now);
            } else if (existing.getCreatedAt() == null) {
                existing.setCreatedAt(now);
            }
            videoMapper.updateById(existing);
        } catch (Exception e) {
            markDbFailed("upsertVideoDetail", e);
        }
    }

    private Video findVideo(String bvid, String url) {
        LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Video::getPlatform, "bilibili");
        if (bvid != null && !bvid.isBlank()) {
            wrapper.eq(Video::getPlatformVid, bvid.trim());
        } else if (url != null && !url.isBlank()) {
            wrapper.eq(Video::getUrl, url.trim());
        } else {
            return null;
        }
        wrapper.last("LIMIT 1");
        return videoMapper.selectOne(wrapper);
    }

    private boolean isStale(Video video) {
        if (video == null) {
            return true;
        }
        LocalDateTime createdAt = video.getCreatedAt();
        if (createdAt == null) {
            return true;
        }
        long hours = Math.max(1, refreshHours);
        return createdAt.plusHours(hours).isBefore(LocalDateTime.now());
    }

    private VideoInfo toVideoInfo(Video video) {
        if (video == null) {
            return null;
        }
        String duration = null;
        if (video.getDurationSec() != null && video.getDurationSec() > 0) {
            int total = video.getDurationSec();
            if (total >= 3600) {
                duration = String.format("%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
            } else {
                duration = String.format("%d:%02d", total / 60, total % 60);
            }
        }
        return VideoInfo.builder()
            .bvid(video.getPlatformVid())
            .title(video.getTitle())
            .url(video.getUrl())
            .duration(duration)
            .tags(video.getTags())
            .description(video.getDescription())
            .build();
    }

    private void applyFields(Video video, VideoInfo info) {
        if (video == null || info == null) {
            return;
        }
        if (info.getTitle() != null && !info.getTitle().isBlank()) {
            video.setTitle(info.getTitle());
        }
        if (info.getTags() != null && !info.getTags().isBlank()) {
            video.setTags(info.getTags());
        }
        if (info.getDescription() != null && !info.getDescription().isBlank()) {
            video.setDescription(info.getDescription());
        }
        Integer durationSec = parseDurationToSeconds(info.getDuration());
        if (durationSec != null) {
            video.setDurationSec(durationSec);
        }
        if (info.getUrl() != null && !info.getUrl().isBlank()) {
            video.setUrl(info.getUrl());
        }
    }

    private boolean hasDetail(VideoInfo info) {
        if (info == null) {
            return false;
        }
        if (info.getTags() != null && !info.getTags().isBlank()) {
            return true;
        }
        if (info.getDescription() != null && !info.getDescription().isBlank()) {
            return true;
        }
        if (info.getPlayCount() != null) {
            return true;
        }
        return info.getCommentCount() != null;
    }

    private String extractBvid(VideoInfo info) {
        if (info == null) {
            return null;
        }
        if (info.getBvid() != null && !info.getBvid().isBlank()) {
            return info.getBvid();
        }
        String url = info.getUrl();
        if (url == null) {
            return null;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/video/(BV[a-zA-Z0-9]+)");
        java.util.regex.Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Integer parseDurationToSeconds(String duration) {
        if (duration == null || duration.isBlank()) {
            return null;
        }
        try {
            String[] parts = duration.trim().split(":");
            if (parts.length == 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int s = Integer.parseInt(parts[2]);
                return h * 3600 + m * 60 + s;
            } else if (parts.length == 2) {
                int m = Integer.parseInt(parts[0]);
                int s = Integer.parseInt(parts[1]);
                return m * 60 + s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isDbUsable() {
        return dbEnabled && dbAvailable.get();
    }

    private void markDbFailed(String op, Exception e) {
        if (e == null) {
            return;
        }
        dbAvailable.set(false);
        if (dbFailureLogged.compareAndSet(false, true)) {
            log.warn("[VideoDetailCache] DB unavailable, disable cache. op={}, reason={}", op, e.getMessage());
        } else {
            log.debug("[VideoDetailCache] op={} failed: {}", op, e.getMessage());
        }
    }

    public record CacheEntry(VideoInfo video, boolean stale, LocalDateTime cachedAt) {
    }
}
