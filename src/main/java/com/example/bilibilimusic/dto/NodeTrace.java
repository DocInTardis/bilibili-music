package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点执行追踪记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeTrace {
    
    /**
     * 节点名称
     */
    private String nodeName;
    
    /**
     * 执行开始时间戳（毫秒）
     */
    private Long startTime;
    
    /**
     * 执行结束时间戳（毫秒）
     */
    private Long endTime;
    
    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误信息（如果失败）
     */
    private String error;
    
    /**
     * 节点输出简要（可选）
     */
    private String output;
}
