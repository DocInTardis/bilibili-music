package com.example.bilibilimusic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 播放列表实体
 */
@Data
@TableName("playlist")
public class Playlist {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long conversationId;
    
    private String name;
    
    private Integer targetCount;
    
    private Integer actualCount;
    
    private String status; // BUILDING / DONE / PARTIAL
    
    private LocalDateTime createdAt;
}
