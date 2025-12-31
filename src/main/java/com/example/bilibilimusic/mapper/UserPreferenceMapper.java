package com.example.bilibilimusic.mapper;

import com.example.bilibilimusic.entity.UserPreference;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户偏好 Mapper
 */
@Mapper
@Repository
public interface UserPreferenceMapper {
    
    /**
     * 保存用户偏好
     */
    @Insert("INSERT INTO user_preference (conversation_id, preference_type, preference_target, weight_score, interaction_count, last_updated, created_at) " +
            "VALUES (#{conversationId}, #{preferenceType}, #{preferenceTarget}, #{weightScore}, #{interactionCount}, #{lastUpdated}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(UserPreference preference);
    
    /**
     * 更新用户偏好
     */
    @Update("UPDATE user_preference SET weight_score = #{weightScore}, interaction_count = #{interactionCount}, last_updated = #{lastUpdated} " +
            "WHERE id = #{id}")
    void update(UserPreference preference);
    
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
