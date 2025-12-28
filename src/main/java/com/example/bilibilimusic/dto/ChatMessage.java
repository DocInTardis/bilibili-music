package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息类型：
     * query（用户查询）、
     * status（阶段状态文本）、
     * stage_update（阶段认知产物）、
     * stream_update（视频判断流式反馈）、
     * result（最终结果）、
     * error（错误）
     */
    private String type;

    /**
     * 简要文本内容（用于状态或简单描述）
     */
    private String content;

    /**
     * 查询限制（仅 query 类型使用）
     */
    private Integer limit;

    /**
     * 摘要（仅 result 类型使用）
     */
    private String summary;

    /**
     * 视频列表（用于结果或阶段展示）
     */
    private java.util.List<VideoInfo> videos;

    /**
     * 垃圾桶候选视频列表（相关推荐）
     */
    private java.util.List<VideoInfo> trashVideos;

    /**
     * 当前 Agent 状态机阶段
     */
    private String stage;

    /**
     * 阶段的结构化认知产物（前端可视化用）
     */
    private java.util.Map<String, Object> payload;
}
