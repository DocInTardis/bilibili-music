package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.OnlineLearningConfigEntry;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OnlineLearningConfigMapper extends BaseMapper<OnlineLearningConfigEntry> {

    @Select("SELECT * FROM online_learning_config WHERE config_key = #{key} LIMIT 1")
    OnlineLearningConfigEntry findByKey(@Param("key") String key);

    @Insert("""
        INSERT INTO online_learning_config(config_key, config_value, updated_at)
        VALUES(#{key}, #{value}, NOW())
        ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), updated_at = NOW()
        """)
    int upsert(@Param("key") String key, @Param("value") String value);
}

