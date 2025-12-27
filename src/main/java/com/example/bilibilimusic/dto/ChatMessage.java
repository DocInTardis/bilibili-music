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
     * 消息类型：query（用户查询）、status（状态更新）、result（结果）、error（错误）
     */
    private String type;

    /**
     * 消息内容
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
     * 视频列表（仅 result 类型使用）
     */
    private List<VideoInfo> videos;
}
