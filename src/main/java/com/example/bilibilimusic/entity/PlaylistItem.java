package com.example.bilibilimusic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 播放列表项实体（有序）
 */
@Data
@TableName("playlist_item")
public class PlaylistItem {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long playlistId;
    
    private Long musicUnitId;
    
    private Long videoId;
    
    private Integer position;
    
    private String addedReason;
    
    private Boolean userLiked;
    
    private Integer weight; // 视频权重，用于个性化推荐，默认1
    
    private LocalDateTime createdAt;
}
