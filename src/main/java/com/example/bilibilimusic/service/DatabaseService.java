package com.example.bilibilimusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.Conversation;
import com.example.bilibilimusic.entity.MusicUnitEntity;
import com.example.bilibilimusic.entity.Playlist;
import com.example.bilibilimusic.entity.PlaylistItem;
import com.example.bilibilimusic.entity.Video;
import com.example.bilibilimusic.mapper.ConversationMapper;
import com.example.bilibilimusic.mapper.MusicUnitMapper;
import com.example.bilibilimusic.mapper.PlaylistItemMapper;
import com.example.bilibilimusic.mapper.PlaylistMapper;
import com.example.bilibilimusic.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseService {

    @Value("${DB_ENABLED:true}")
    private boolean dbEnabled;

    private final AtomicBoolean dbAvailable = new AtomicBoolean(true);
    private final AtomicBoolean dbFailureLogged = new AtomicBoolean(false);

    private final AtomicLong memoryConversationId = new AtomicLong(1);
    private final AtomicLong memoryPlaylistId = new AtomicLong(1);
    private final AtomicLong memoryVideoId = new AtomicLong(1);
    private final Map<Long, Conversation> memoryConversations = new ConcurrentHashMap<>();
    private final Map<Long, Playlist> memoryPlaylists = new ConcurrentHashMap<>();
    private final Map<String, Video> memoryVideosByBvid = new ConcurrentHashMap<>();

    private final ConversationMapper conversationMapper;
    private final PlaylistMapper playlistMapper;
    private final VideoMapper videoMapper;
    private final MusicUnitMapper musicUnitMapper;
    private final PlaylistItemMapper playlistItemMapper;
    private final BilibiliSearchService bilibiliSearchService;

    public Conversation getOrCreateActiveConversation() {
        if (!isDbUsable()) {
            return getOrCreateMemoryConversation();
        }
        try {
            LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Conversation::getStatus, "ACTIVE")
                .orderByDesc(Conversation::getCreatedAt)
                .last("LIMIT 1");
            Conversation conversation = conversationMapper.selectOne(wrapper);
            if (conversation == null) {
                conversation = new Conversation();
                conversation.setStatus("ACTIVE");
                conversation.setUserId(1L);
                conversation.setCreatedAt(LocalDateTime.now());
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.insert(conversation);
                log.info("Created conversation: id={}", conversation.getId());
            } else if (conversation.getUserId() == null) {
                conversation.setUserId(1L);
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conversation);
            }
            return conversation;
        } catch (Exception e) {
            markDbFailed("getOrCreateActiveConversation", e);
            return getOrCreateMemoryConversation();
        }
    }

    public Playlist createPlaylist(Long conversationId, String name, Integer targetCount) {
        if (!isDbUsable()) {
            return createMemoryPlaylist(conversationId, name, targetCount);
        }
        try {
            Playlist playlist = new Playlist();
            playlist.setConversationId(conversationId);
            playlist.setName(name);
            playlist.setTargetCount(targetCount);
            playlist.setActualCount(0);
            playlist.setStatus("BUILDING");
            playlist.setCreatedAt(LocalDateTime.now());
            playlistMapper.insert(playlist);

            Conversation conversation = conversationMapper.selectById(conversationId);
            if (conversation != null) {
                conversation.setCurrentPlaylistId(playlist.getId());
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conversation);
            }
            return playlist;
        } catch (Exception e) {
            markDbFailed("createPlaylist", e);
            return createMemoryPlaylist(conversationId, name, targetCount);
        }
    }

    public Video saveOrUpdateVideo(VideoInfo videoInfo) {
        if (!isDbUsable()) {
            return saveOrUpdateMemoryVideo(videoInfo);
        }
        try {
            if (videoInfo == null) {
                return null;
            }
            String bvid = extractBvid(videoInfo);
            if (bvid == null) {
                log.warn("Cannot extract BVID from url={}", videoInfo.getUrl());
                return null;
            }

            LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Video::getPlatform, "bilibili")
                .eq(Video::getPlatformVid, bvid);
            Video video = videoMapper.selectOne(wrapper);
            if (video == null) {
                video = new Video();
                video.setPlatform("bilibili");
                video.setPlatformVid(bvid);
                video.setCreatedAt(LocalDateTime.now());
                videoMapper.insert(video);
            }

            video.setTitle(videoInfo.getTitle());
            video.setTags(videoInfo.getTags());
            video.setDescription(videoInfo.getDescription());
            video.setDurationSec(parseDurationToSeconds(videoInfo.getDuration()));
            video.setUrl(videoInfo.getUrl());
            videoMapper.updateById(video);
            return video;
        } catch (Exception e) {
            markDbFailed("saveOrUpdateVideo", e);
            return saveOrUpdateMemoryVideo(videoInfo);
        }
    }

    @Transactional
    public void addMusicToPlaylist(Long playlistId, String title, String artist,
                                   Video video, String reason, Integer position) {
        if (!isDbUsable()) {
            updateMemoryPlaylistCount(playlistId);
            return;
        }
        try {
            MusicUnitEntity musicUnit = new MusicUnitEntity();
            musicUnit.setTitle(title);
            musicUnit.setArtist(artist);
            musicUnit.setDurationSec(video != null ? video.getDurationSec() : null);
            musicUnit.setSource("bilibili");
            musicUnit.setCreatedAt(LocalDateTime.now());
            musicUnitMapper.insert(musicUnit);

            PlaylistItem item = new PlaylistItem();
            item.setPlaylistId(playlistId);
            item.setMusicUnitId(musicUnit.getId());
            item.setVideoId(video != null ? video.getId() : null);
            item.setPosition(position);
            item.setAddedReason(reason);
            item.setUserLiked(false);
            item.setWeight(1);
            item.setCreatedAt(LocalDateTime.now());
            playlistItemMapper.insert(item);

            Playlist playlist = playlistMapper.selectById(playlistId);
            if (playlist != null) {
                int current = playlist.getActualCount() != null ? playlist.getActualCount() : 0;
                playlist.setActualCount(current + 1);
                playlistMapper.updateById(playlist);
            }
        } catch (Exception e) {
            markDbFailed("addMusicToPlaylist", e);
        }
    }

    @Transactional
    public void addVideoToPlaylistByUrl(Long playlistId, String url, String reason) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (!isDbUsable()) {
            VideoInfo videoInfo = bilibiliSearchService.fetchByUrl(url);
            if (videoInfo == null) {
                log.warn("Failed to fetch video info by url={}", url);
                return;
            }
            videoInfo.setBvid(extractBvid(url));
            saveOrUpdateMemoryVideo(videoInfo);
            updateMemoryPlaylistCount(playlistId);
            return;
        }
        try {
            VideoInfo videoInfo = bilibiliSearchService.fetchByUrl(url);
            if (videoInfo == null) {
                log.warn("Failed to fetch video info by url={}", url);
                return;
            }
            videoInfo.setBvid(extractBvid(url));
            Video video = saveOrUpdateVideo(videoInfo);
            if (video == null) {
                log.warn("Failed to persist video by url={}", url);
                return;
            }
            LambdaQueryWrapper<PlaylistItem> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PlaylistItem::getPlaylistId, playlistId)
                .orderByDesc(PlaylistItem::getPosition)
                .last("LIMIT 1");
            PlaylistItem last = playlistItemMapper.selectOne(wrapper);
            int nextPosition = (last != null && last.getPosition() != null) ? last.getPosition() + 1 : 1;
            addMusicToPlaylist(playlistId, videoInfo.getTitle(), videoInfo.getAuthor(), video, reason, nextPosition);
        } catch (Exception e) {
            markDbFailed("addVideoToPlaylistByUrl", e);
        }
    }

    public List<VideoInfo> getRandomRecommendations(int limit) {
        if (!isDbUsable()) {
            return new ArrayList<>();
        }
        try {
            int actualLimit = limit > 0 ? limit : 10;
            List<Video> videos = videoMapper.selectRandomVideos(actualLimit);
            List<VideoInfo> result = new ArrayList<>();
            for (Video v : videos) {
                if (v == null) {
                    continue;
                }
                String durationStr = null;
                if (v.getDurationSec() != null && v.getDurationSec() > 0) {
                    int total = v.getDurationSec();
                    durationStr = String.format("%d:%02d", total / 60, total % 60);
                }
                result.add(VideoInfo.builder()
                    .bvid(v.getPlatformVid())
                    .title(v.getTitle())
                    .url(v.getUrl())
                    .author("unknown")
                    .duration(durationStr)
                    .tags(v.getTags())
                    .description(v.getDescription())
                    .build());
            }
            return result;
        } catch (Exception e) {
            markDbFailed("getRandomRecommendations", e);
            return new ArrayList<>();
        }
    }

    public void finishPlaylist(Long playlistId, boolean isPartial) {
        if (!isDbUsable()) {
            updateMemoryPlaylistStatus(playlistId, isPartial);
            return;
        }
        try {
            Playlist playlist = playlistMapper.selectById(playlistId);
            if (playlist != null) {
                playlist.setStatus(isPartial ? "PARTIAL" : "DONE");
                playlistMapper.updateById(playlist);
            }
        } catch (Exception e) {
            markDbFailed("finishPlaylist", e);
        }
    }

    public void increaseItemWeight(Long itemId) {
        if (!isDbUsable()) {
            return;
        }
        try {
            PlaylistItem item = playlistItemMapper.selectById(itemId);
            if (item != null) {
                int current = item.getWeight() != null ? item.getWeight() : 1;
                item.setWeight(current + 1);
                item.setUserLiked(true);
                playlistItemMapper.updateById(item);
            }
        } catch (Exception e) {
            markDbFailed("increaseItemWeight", e);
        }
    }

    public void savePlaylistWithName(Long playlistId, String name) {
        if (!isDbUsable()) {
            updateMemoryPlaylistName(playlistId, name);
            return;
        }
        try {
            Playlist playlist = playlistMapper.selectById(playlistId);
            if (playlist != null) {
                playlist.setName(name);
                playlistMapper.updateById(playlist);
            } else {
                log.warn("Playlist not found: playlistId={}", playlistId);
            }
        } catch (Exception e) {
            markDbFailed("savePlaylistWithName", e);
        }
    }

    public List<Conversation> getAllConversations() {
        if (!isDbUsable()) {
            return getMemoryConversations();
        }
        try {
            LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByDesc(Conversation::getUpdatedAt);
            return conversationMapper.selectList(wrapper);
        } catch (Exception e) {
            markDbFailed("getAllConversations", e);
            return getMemoryConversations();
        }
    }

    public Conversation createNewConversation(String name) {
        if (!isDbUsable()) {
            return createMemoryConversation();
        }
        try {
            LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Conversation::getStatus, "ACTIVE");
            List<Conversation> activeConversations = conversationMapper.selectList(wrapper);
            for (Conversation c : activeConversations) {
                c.setStatus("FINISHED");
                conversationMapper.updateById(c);
            }

            Conversation conversation = new Conversation();
            conversation.setStatus("ACTIVE");
            conversation.setUserId(1L);
            conversation.setCreatedAt(LocalDateTime.now());
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.insert(conversation);
            return conversation;
        } catch (Exception e) {
            markDbFailed("createNewConversation", e);
            return createMemoryConversation();
        }
    }

    public Video findVideoByBvid(String bvid) {
        if (bvid == null || bvid.isBlank()) {
            return null;
        }
        if (!isDbUsable()) {
            return memoryVideosByBvid.get(bvid);
        }
        try {
            LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Video::getPlatform, "bilibili")
                .eq(Video::getPlatformVid, bvid)
                .last("LIMIT 1");
            return videoMapper.selectOne(wrapper);
        } catch (Exception e) {
            markDbFailed("findVideoByBvid", e);
            return memoryVideosByBvid.get(bvid);
        }
    }

    public void switchToConversation(Long conversationId) {
        if (!isDbUsable()) {
            switchMemoryConversation(conversationId);
            return;
        }
        try {
            LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Conversation::getStatus, "ACTIVE");
            List<Conversation> activeConversations = conversationMapper.selectList(wrapper);
            for (Conversation c : activeConversations) {
                c.setStatus("FINISHED");
                conversationMapper.updateById(c);
            }

            Conversation conversation = conversationMapper.selectById(conversationId);
            if (conversation != null) {
                conversation.setStatus("ACTIVE");
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conversation);
            }
        } catch (Exception e) {
            markDbFailed("switchToConversation", e);
        }
    }

    public void deleteConversation(Long conversationId) {
        if (!isDbUsable()) {
            memoryConversations.remove(conversationId);
            return;
        }
        try {
            conversationMapper.deleteById(conversationId);
        } catch (Exception e) {
            markDbFailed("deleteConversation", e);
        }
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
            log.warn("[Database] DB unavailable, switching to in-memory mode. op={}, reason={}", op, e.getMessage());
        } else {
            log.debug("[Database] op={} failed: {}", op, e.getMessage());
        }
    }

    private Conversation getOrCreateMemoryConversation() {
        Conversation active = null;
        for (Conversation c : memoryConversations.values()) {
            if (c != null && "ACTIVE".equals(c.getStatus())) {
                active = c;
                break;
            }
        }
        if (active == null) {
            active = createMemoryConversation();
        }
        if (active.getUpdatedAt() == null) {
            active.setUpdatedAt(LocalDateTime.now());
        }
        return active;
    }

    private Conversation createMemoryConversation() {
        for (Conversation c : memoryConversations.values()) {
            if (c != null && "ACTIVE".equals(c.getStatus())) {
                c.setStatus("FINISHED");
                c.setUpdatedAt(LocalDateTime.now());
            }
        }
        Conversation conversation = new Conversation();
        long id = memoryConversationId.getAndIncrement();
        LocalDateTime now = LocalDateTime.now();
        conversation.setId(id);
        conversation.setStatus("ACTIVE");
        conversation.setUserId(1L);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        memoryConversations.put(id, conversation);
        return conversation;
    }

    private List<Conversation> getMemoryConversations() {
        List<Conversation> list = new ArrayList<>(memoryConversations.values());
        list.sort(Comparator.comparing(Conversation::getUpdatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return list;
    }

    private Playlist createMemoryPlaylist(Long conversationId, String name, Integer targetCount) {
        Playlist playlist = new Playlist();
        long id = memoryPlaylistId.getAndIncrement();
        playlist.setId(id);
        playlist.setConversationId(conversationId);
        playlist.setName(name);
        playlist.setTargetCount(targetCount);
        playlist.setActualCount(0);
        playlist.setStatus("BUILDING");
        playlist.setCreatedAt(LocalDateTime.now());
        memoryPlaylists.put(id, playlist);

        Conversation conv = memoryConversations.get(conversationId);
        if (conv != null) {
            conv.setCurrentPlaylistId(id);
            conv.setUpdatedAt(LocalDateTime.now());
        }
        return playlist;
    }

    private Video saveOrUpdateMemoryVideo(VideoInfo videoInfo) {
        if (videoInfo == null) {
            return null;
        }
        String bvid = extractBvid(videoInfo);
        if (bvid == null) {
            return null;
        }
        Video video = memoryVideosByBvid.get(bvid);
        if (video == null) {
            video = new Video();
            video.setId(memoryVideoId.getAndIncrement());
            video.setPlatform("bilibili");
            video.setPlatformVid(bvid);
            video.setCreatedAt(LocalDateTime.now());
            memoryVideosByBvid.put(bvid, video);
        }
        video.setTitle(videoInfo.getTitle());
        video.setTags(videoInfo.getTags());
        video.setDescription(videoInfo.getDescription());
        video.setDurationSec(parseDurationToSeconds(videoInfo.getDuration()));
        video.setUrl(videoInfo.getUrl());
        return video;
    }

    private void updateMemoryPlaylistCount(Long playlistId) {
        Playlist playlist = memoryPlaylists.get(playlistId);
        if (playlist != null) {
            int current = playlist.getActualCount() != null ? playlist.getActualCount() : 0;
            playlist.setActualCount(current + 1);
        }
    }

    private void updateMemoryPlaylistStatus(Long playlistId, boolean isPartial) {
        Playlist playlist = memoryPlaylists.get(playlistId);
        if (playlist != null) {
            playlist.setStatus(isPartial ? "PARTIAL" : "DONE");
        }
    }

    private void updateMemoryPlaylistName(Long playlistId, String name) {
        Playlist playlist = memoryPlaylists.get(playlistId);
        if (playlist != null) {
            playlist.setName(name);
        }
    }

    private void switchMemoryConversation(Long conversationId) {
        for (Conversation c : memoryConversations.values()) {
            if (c == null) {
                continue;
            }
            if (c.getId() != null && c.getId().equals(conversationId)) {
                c.setStatus("ACTIVE");
                c.setUpdatedAt(LocalDateTime.now());
            } else if ("ACTIVE".equals(c.getStatus())) {
                c.setStatus("FINISHED");
                c.setUpdatedAt(LocalDateTime.now());
            }
        }
    }

    private String extractBvid(VideoInfo videoInfo) {
        if (videoInfo == null) {
            return null;
        }
        String bvid = videoInfo.getBvid();
        if (bvid != null && !bvid.isBlank()) {
            return bvid;
        }
        return extractBvid(videoInfo.getUrl());
    }

    private String extractBvid(String url) {
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
}
