package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.UserBehaviorEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户行为事件 Mapper - 基于会话维度的简单聚合查询
 */
@Mapper
public interface UserBehaviorEventMapper extends BaseMapper<UserBehaviorEvent> {

    /**
     * 统计某个会话下的行为事件总数
     */
    @Select("SELECT COUNT(*) FROM user_behavior_event WHERE conversation_id = #{conversationId}")
    long countByConversationId(@Param("conversationId") Long conversationId);
}
