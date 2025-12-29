package com.example.bilibilimusic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 会话实体，一个聊天窗口
 */
@Data
@TableName("conversation")
public class Conversation {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long userId;
    
    private Long currentPlaylistId;
    
    private String status; // ACTIVE / FINISHED
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
}
