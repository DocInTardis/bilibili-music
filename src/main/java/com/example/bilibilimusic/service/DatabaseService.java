package com.example.bilibilimusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.*;
import com.example.bilibilimusic.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 数据库持久化服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseService {

    private final ConversationMapper conversationMapper;
    private final PlaylistMapper playlistMapper;
    private final VideoMapper videoMapper;
    private final MusicUnitMapper musicUnitMapper;
    private final PlaylistItemMapper playlistItemMapper;

    /**
     * 创建或获取当前活跃会话
     */
    public Conversation getOrCreateActiveConversation() {
        // 查找当前活跃的会话
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Conversation::getStatus, "ACTIVE")
               .orderByDesc(Conversation::getCreatedAt)
               .last("LIMIT 1");
        
        Conversation conversation = conversationMapper.selectOne(wrapper);
        
        if (conversation == null) {
            // 创建新会话
            conversation = new Conversation();
            conversation.setStatus("ACTIVE");
            conversation.setCreatedAt(LocalDateTime.now());
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.insert(conversation);
            log.info("创建新会话，ID: {}", conversation.getId());
        }
        
        return conversation;
    }

    /**
     * 创建播放列表
     */
    public Playlist createPlaylist(Long conversationId, String name, Integer targetCount) {
        Playlist playlist = new Playlist();
        playlist.setConversationId(conversationId);
        playlist.setName(name);
        playlist.setTargetCount(targetCount);
        playlist.setActualCount(0);
        playlist.setStatus("BUILDING");
        playlist.setCreatedAt(LocalDateTime.now());
        
        playlistMapper.insert(playlist);
        log.info("创建播放列表，ID: {}, 名称: {}", playlist.getId(), name);
        
        // 更新会话的当前播放列表ID
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null) {
            conversation.setCurrentPlaylistId(playlist.getId());
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
        
        return playlist;
    }

    /**
     * 保存或更新视频（去重）
     */
    public Video saveOrUpdateVideo(VideoInfo videoInfo) {
        // 从URL提取BVID
        String bvid = extractBvid(videoInfo.getUrl());
        if (bvid == null) {
            log.warn("无法从URL提取BVID: {}", videoInfo.getUrl());
            return null;
        }

        // 查找是否已存在
        LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Video::getPlatform, "bilibili")
               .eq(Video::getPlatformVid, bvid);
        
        Video video = videoMapper.selectOne(wrapper);
        
        if (video == null) {
            // 新视频，插入
            video = new Video();
            video.setPlatform("bilibili");
            video.setPlatformVid(bvid);
            video.setTitle(videoInfo.getTitle());
            video.setTags(videoInfo.getTags());
            video.setDescription(videoInfo.getDescription());
            video.setDurationSec(parseDurationToSeconds(videoInfo.getDuration()));
            video.setUrl(videoInfo.getUrl());
            video.setCreatedAt(LocalDateTime.now());
            
            videoMapper.insert(video);
            log.debug("保存新视频到数据库: {} - {}", bvid, videoInfo.getTitle());
        } else {
            // 已存在，更新信息
            video.setTitle(videoInfo.getTitle());
            video.setTags(videoInfo.getTags());
            video.setDescription(videoInfo.getDescription());
            video.setDurationSec(parseDurationToSeconds(videoInfo.getDuration()));
            
            videoMapper.updateById(video);
            log.debug("更新视频信息: {} - {}", bvid, videoInfo.getTitle());
        }
        
        return video;
    }

    /**
     * 添加歌曲到播放列表
     */
    @Transactional
    public void addMusicToPlaylist(Long playlistId, String title, String artist, 
                                    Video video, String reason, Integer position) {
        // 创建音乐单元
        MusicUnitEntity musicUnit = new MusicUnitEntity();
        musicUnit.setTitle(title);
        musicUnit.setArtist(artist);
        musicUnit.setDurationSec(video.getDurationSec());
        musicUnit.setSource("bilibili");
        musicUnit.setCreatedAt(LocalDateTime.now());
        
        musicUnitMapper.insert(musicUnit);
        
        // 创建播放列表项
        PlaylistItem item = new PlaylistItem();
        item.setPlaylistId(playlistId);
        item.setMusicUnitId(musicUnit.getId());
        item.setVideoId(video.getId());
        item.setPosition(position);
        item.setAddedReason(reason);
        item.setUserLiked(false);
        item.setWeight(1); // 默认权重为1
        item.setCreatedAt(LocalDateTime.now());
        
        playlistItemMapper.insert(item);
        
        // 更新播放列表的实际数量
        Playlist playlist = playlistMapper.selectById(playlistId);
        if (playlist != null) {
            playlist.setActualCount(playlist.getActualCount() + 1);
            playlistMapper.updateById(playlist);
        }
        
        log.info("添加歌曲到播放列表: {} - {}, 位置: {}", title, artist, position);
    }

    /**
     * 完成播放列表构建
     */
    public void finishPlaylist(Long playlistId, boolean isPartial) {
        Playlist playlist = playlistMapper.selectById(playlistId);
        if (playlist != null) {
            playlist.setStatus(isPartial ? "PARTIAL" : "DONE");
            playlistMapper.updateById(playlist);
            log.info("播放列表构建完成，ID: {}, 状态: {}", playlistId, playlist.getStatus());
        }
    }
    
    /**
     * 增加播放列表项权重（点击爱心）
     */
    public void increaseItemWeight(Long itemId) {
        PlaylistItem item = playlistItemMapper.selectById(itemId);
        if (item != null) {
            Integer currentWeight = item.getWeight() != null ? item.getWeight() : 1;
            item.setWeight(currentWeight + 1);
            item.setUserLiked(true);
            playlistItemMapper.updateById(item);
            log.info("增加视频权重: itemId={}, 新权重={}", itemId, item.getWeight());
        }
    }
    
    /**
     * 保存播放列表并重命名
     */
    public void savePlaylistWithName(Long playlistId, String name) {
        Playlist playlist = playlistMapper.selectById(playlistId);
        if (playlist != null) {
            playlist.setName(name);
            playlistMapper.updateById(playlist);
            log.info("保存播放列表: playlistId={}, 新名称={}", playlistId, name);
        } else {
            log.warn("播放列表不存在: playlistId={}", playlistId);
        }
    }
    
    /**
     * 获取所有对话窗口
     */
    public java.util.List<Conversation> getAllConversations() {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Conversation::getUpdatedAt);
        return conversationMapper.selectList(wrapper);
    }
    
    /**
     * 创建新对话窗口
     */
    public Conversation createNewConversation(String name) {
        // 将其他所有对话设为FINISHED
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Conversation::getStatus, "ACTIVE");
        java.util.List<Conversation> activeConversations = conversationMapper.selectList(wrapper);
        activeConversations.forEach(c -> {
            c.setStatus("FINISHED");
            conversationMapper.updateById(c);
        });
        
        // 创建新对话
        Conversation conversation = new Conversation();
        conversation.setStatus("ACTIVE");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conversation);
        log.info("创建新对话窗口，ID: {}", conversation.getId());
        return conversation;
    }
    
    /**
     * 切换到指定对话窗口
     */
    public void switchToConversation(Long conversationId) {
        // 将其他所有对话设为FINISHED
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Conversation::getStatus, "ACTIVE");
        java.util.List<Conversation> activeConversations = conversationMapper.selectList(wrapper);
        activeConversations.forEach(c -> {
            c.setStatus("FINISHED");
            conversationMapper.updateById(c);
        });
        
        // 将目标对话设为ACTIVE
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null) {
            conversation.setStatus("ACTIVE");
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
            log.info("切换到对话窗口: conversationId={}", conversationId);
        }
    }
    
    /**
     * 删除对话窗口
     */
    public void deleteConversation(Long conversationId) {
        conversationMapper.deleteById(conversationId);
        log.info("删除对话窗口: conversationId={}", conversationId);
    }

    /**
     * 从URL提取BVID
     */
    private String extractBvid(String url) {
        if (url == null) return null;
        
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/video/(BV[a-zA-Z0-9]+)");
        java.util.regex.Matcher matcher = pattern.matcher(url);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 将时长字符串转换为秒
     */
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
