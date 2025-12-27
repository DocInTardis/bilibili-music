package com.example.bilibilimusic.context;

import lombok.Builder;
import lombok.Data;

/**
 * 用户意图建模
 */
@Data
@Builder
public class UserIntent {
    
    /**
     * 原始查询
     */
    private String query;
    
    /**
     * 期望视频数量
     */
    private int limit;
    
    /**
     * 用户偏好（可选）
     */
    private String preference;
    
    /**
     * 是否下载为 MP3
     */
    private boolean downloadAsMp3;
}
