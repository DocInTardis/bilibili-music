package com.example.bilibilimusic.dto;

import lombok.Data;

/**
 * 前端用户行为上报请求
 */
@Data
public class UserBehaviorRequest {

    /** 会话ID */
    private Long conversationId;

    /** 行为类型，对应 UserBehaviorEvent.BehaviorType 名称，例如 LIKE / DISLIKE / SKIP / PLAY_PARTIAL / REMOVE 等 */
    private String behaviorType;

    /** 目标类型：video / artist / keyword */
    private String targetType;

    /** 目标标识（如视频 BVID、艺人名、关键词） */
    private String targetId;

    /** 行为强度（可选），0.0-1.0；为空时使用枚举默认强度 */
    private Double intensity;

    /** 额外上下文（JSON 字符串），如播放时长、总时长、是否手动删除等 */
    private String contextJson;
}
