package com.example.bilibilimusic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频缓存实体（去重用）
 */
@Data
@TableName("video")
public class Video {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String platform; // bilibili
    
    private String platformVid; // BVID
    
    private String title;
    
    private String tags;
    
    private String description;
    
    private Integer durationSec;
    
    private String url;
    
    private LocalDateTime createdAt;
}
