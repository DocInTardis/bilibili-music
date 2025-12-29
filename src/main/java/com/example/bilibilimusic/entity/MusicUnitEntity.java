package com.example.bilibilimusic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 音乐单元实体（一首歌）
 */
@Data
@TableName("music_unit")
public class MusicUnitEntity {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String title;
    
    private String artist;
    
    private Integer durationSec;
    
    private String style;
    
    private String scene;
    
    private String source; // bilibili
    
    private LocalDateTime createdAt;
}
