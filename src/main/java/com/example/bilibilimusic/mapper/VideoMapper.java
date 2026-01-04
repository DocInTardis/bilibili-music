package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.Video;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 视频 Mapper
 */
@Mapper
public interface VideoMapper extends BaseMapper<Video> {

    /**
     * 随机获取若干视频（用于每日推荐榜单）
     */
    @Select("SELECT * FROM video ORDER BY RAND() LIMIT #{limit}")
    List<Video> selectRandomVideos(@Param("limit") int limit);
}
