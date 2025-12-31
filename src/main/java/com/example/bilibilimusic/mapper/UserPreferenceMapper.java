package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.UserPreference;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户偏好 Mapper - 使用 MyBatis-Plus
 */
@Mapper
public interface UserPreferenceMapper extends BaseMapper<UserPreference> {
    
    /**
     * 根据会话ID和类型+目标查找偏好
     */
    @Select("SELECT * FROM user_preference WHERE conversation_id = #{conversationId} AND preference_type = #{preferenceType} AND preference_target = #{preferenceTarget}")
    UserPreference findByConversationAndTypeAndTarget(@Param("conversationId") Long conversationId, 
                                                       @Param("preferenceType") String preferenceType, 
                                                       @Param("preferenceTarget") String preferenceTarget);
    
    /**
     * 根据会话ID查找所有偏好
     */
    @Select("SELECT * FROM user_preference WHERE conversation_id = #{conversationId} ORDER BY weight_score DESC, last_updated DESC")
    List<UserPreference> findByConversationId(@Param("conversationId") Long conversationId);
    
    /**
     * 根据会话ID和类型查找偏好
     */
    @Select("SELECT * FROM user_preference WHERE conversation_id = #{conversationId} AND preference_type = #{preferenceType} ORDER BY weight_score DESC")
    List<UserPreference> findByConversationIdAndType(@Param("conversationId") Long conversationId, 
                                                       @Param("preferenceType") String preferenceType);
}
