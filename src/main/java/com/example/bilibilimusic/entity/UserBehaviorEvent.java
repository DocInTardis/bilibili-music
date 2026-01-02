package com.example.bilibilimusic.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户行为事件实体 - 记录用户的详细行为数据
 * 
 * 用于构建完整的"行为 → 偏好 → 决策"闭环：
 * 1. 捕获用户的所有交互行为
 * 2. 根据行为类型和上下文计算偏好权重变化
 * 3. 支持时间衰减和反馈强化
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBehaviorEvent {
    
    private Long id;
    
    /**
     * 会话ID（关联到具体会话）
     */
    private Long conversationId;
    
    /**
     * 行为类型
     */
    private BehaviorType behaviorType;
    
    /**
     * 行为目标类型（video/artist/keyword）
     */
    private String targetType;
    
    /**
     * 行为目标（视频BV号/艺人名/关键词）
     */
    private String targetId;
    
    /**
     * 行为强度（0.0-1.0）
     * - 点赞: 0.5
     * - 收藏: 0.8
     * - 播放完成度: 实际完成度（0.0-1.0）
     * - 跳过: -0.3
     * - 删除: -0.5
     * - 分享: 1.0
     */
    private Double intensity;
    
    /**
     * 行为上下文（JSON格式）
     * 例如：播放时长、停止位置、设备信息等
     */
    private String contextJson;
    
    /**
     * 行为发生时间
     */
    private LocalDateTime occurredAt;
    
    /**
     * 是否已应用到偏好权重
     */
    private Boolean applied;
    
    /**
     * 行为类型枚举
     */
    public enum BehaviorType {
        // 正向行为（增加偏好）
        LIKE(0.5, "点赞"),
        FAVORITE(0.8, "收藏"),
        PLAY_COMPLETE(1.0, "播放完成"),
        SHARE(1.0, "分享"),
        ADD_TO_PLAYLIST(0.6, "加入播放列表"),
        
        // 中性行为（根据完成度决定）
        PLAY_PARTIAL(0.0, "部分播放"),  // intensity 由实际播放完成度决定
        
        // 负向行为（减少偏好）
        SKIP(-0.3, "跳过"),
        REMOVE(-0.5, "删除"),
        DISLIKE(-0.6, "不喜欢"),
        REPORT(-1.0, "举报");
        
        private final double defaultIntensity;
        private final String description;
        
        BehaviorType(double defaultIntensity, String description) {
            this.defaultIntensity = defaultIntensity;
            this.description = description;
        }
        
        public double getDefaultIntensity() {
            return defaultIntensity;
        }
        
        public String getDescription() {
            return description;
        }
        
        /**
         * 是否是正向行为
         */
        public boolean isPositive() {
            return defaultIntensity > 0;
        }
        
        /**
         * 是否是负向行为
         */
        public boolean isNegative() {
            return defaultIntensity < 0;
        }
    }
}

