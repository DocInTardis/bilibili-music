package com.example.bilibilimusic.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户偏好实体 - 记录用户对视频/艺人的偏好权重
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreference {
    
    private Long id;
    
    /**
     * 会话ID（关联到具体会话）
     */
    private Long conversationId;
    
    /**
     * 偏好类型：video（视频）/ artist（艺人）/ keyword（关键词）
     */
    private String preferenceType;
    
    /**
     * 偏好目标（视频BV号/艺人名/关键词）
     */
    private String preferenceTarget;
    
    /**
     * 权重分数（点赞+1，收藏+2，可累加）
     */
    private Integer weightScore;
    
    /**
     * 交互次数（点赞/收藏次数）
     */
    private Integer interactionCount;
    
    /**
     * 最后更新时间
     */
    private LocalDateTime lastUpdated;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
