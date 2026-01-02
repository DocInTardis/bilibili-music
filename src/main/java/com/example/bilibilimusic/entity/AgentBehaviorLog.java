package com.example.bilibilimusic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 行为日志实体
 * 
 * 用途：
 * 1. 调试：记录 Agent 执行每一步的详细信息
 * 2. 可视化：展示 Agent 执行路径
 * 3. 评估：后续分析和优化依据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_behavior_log")
public class AgentBehaviorLog {
    
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 播放列表ID（关联）
     */
    private Long playlistId;
    
    /**
     * 会话ID（关联）
     */
    private Long conversationId;
    
    /**
     * 行为类型
     * NODE_ENTER, NODE_EXIT, EDGE_TRANSITION, LLM_CALL, CACHE_HIT, ERROR
     */
    private String behaviorType;
    
    /**
     * 节点名称（如果是节点行为）
     */
    private String nodeName;
    
    /**
     * 边名称（如果是边转移）
     */
    private String edgeName;
    
    /**
     * 源节点（边转移）
     */
    private String sourceNode;
    
    /**
     * 目标节点（边转移）
     */
    private String targetNode;
    
    /**
     * 行为描述
     */
    private String description;
    
    /**
     * 输入数据（JSON）
     */
    private String inputData;
    
    /**
     * 输出数据（JSON）
     */
    private String outputData;
    
    /**
     * 执行时长（毫秒）
     */
    private Long durationMs;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
    
    /**
     * Prompt 模板版本（如果是 LLM 调用）
     */
    private String promptVersion;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
