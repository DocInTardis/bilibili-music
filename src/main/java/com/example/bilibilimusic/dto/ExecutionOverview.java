package com.example.bilibilimusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 执行概要，用于可观测性列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionOverview {

    private Long playlistId;
    private Long conversationId;

    /** 最近一次行为时间 */
    private LocalDateTime lastTime;

    /** 节点退出次数（近似节点执行次数） */
    private int nodeExitCount;

    /** 失败的节点次数 */
    private int failedNodeCount;

    /** 平均节点耗时（毫秒） */
    private long avgNodeDurationMs;

    /** 是否存在错误 */
    private boolean hasError;

    /** 最后一个失败节点名称列表（可选，用于展示） */
    private java.util.List<String> failedNodes;

    /** 最近一次执行ID（用于 Debug/对比） */
    private String latestExecutionId;
}
