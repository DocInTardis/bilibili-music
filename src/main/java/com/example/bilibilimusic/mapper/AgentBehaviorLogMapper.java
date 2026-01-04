package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.AgentBehaviorLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 行为日志 Mapper
 */
@Mapper
public interface AgentBehaviorLogMapper extends BaseMapper<AgentBehaviorLog> {

    /**
     * 按时间倒序获取最近的行为日志（用于聚合最近 N 次执行）
     */
    @Select("SELECT * FROM agent_behavior_log ORDER BY created_at DESC LIMIT #{limit}")
    List<AgentBehaviorLog> selectLatestLogs(@Param("limit") int limit);

    /**
     * 获取某时间点之后的错误日志
     */
    @Select("SELECT * FROM agent_behavior_log WHERE behavior_type = 'ERROR' AND created_at >= #{since} ORDER BY created_at DESC")
    List<AgentBehaviorLog> selectErrorsSince(@Param("since") LocalDateTime since);
}
