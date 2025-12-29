package com.example.bilibilimusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bilibilimusic.entity.PlaylistItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 播放列表项 Mapper
 */
@Mapper
public interface PlaylistItemMapper extends BaseMapper<PlaylistItem> {
}
