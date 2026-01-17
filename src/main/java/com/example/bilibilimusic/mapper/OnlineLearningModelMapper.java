package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.OnlineLearningModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OnlineLearningModelMapper extends BaseMapper<OnlineLearningModel> {

    @Select("SELECT * FROM online_learning_model WHERE model_name = #{modelName} AND model_version = #{version} LIMIT 1")
    OnlineLearningModel findByNameAndVersion(@Param("modelName") String modelName, @Param("version") String version);

    @Select("SELECT * FROM online_learning_model WHERE model_name = #{modelName} ORDER BY created_at DESC LIMIT #{limit}")
    List<OnlineLearningModel> listRecent(@Param("modelName") String modelName, @Param("limit") int limit);
}

