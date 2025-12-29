package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.Playlist;
import org.apache.ibatis.annotations.Mapper;

/**
 * 播放列表 Mapper
 */
@Mapper
public interface PlaylistMapper extends BaseMapper<Playlist> {
}
