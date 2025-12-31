package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 边决策追踪记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeTrace {
    
    /**
     * 起始节点
     */
    private String fromNode;
    
    /**
     * 目标节点
     */
    private String toNode;
    
    /**
     * 决策时间戳（毫秒）
     */
    private Long timestamp;
    
    /**
     * 决策原因/条件
     */
    private String reason;
    
    /**
     * 是否是循环边
     */
    private Boolean isLoop;
}
