package com.example.bilibilimusic.dto;

import lombok.Builder;
import lombok.Data;

/**
 * MusicUnit 表示真正的“音乐单元”（一首歌或一组歌），视频只是其载体来源。
 */
@Data
@Builder
public class MusicUnit {

    /** 展示用标题（可以来自视频标题或更细致的描述） */
    private String title;

    /** 艺术家 / 演唱者 */
    private String artist;

    /** 来源视频 */
    private VideoInfo sourceVideo;

    /** 估算该视频中包含的歌曲数量 */
    private int estimatedCount;

    /** 判断理由（为什么认为该视频适合作为音乐来源） */
    private String reason;

    /** 是否为合集 / Mix / Playlist 风格的视频 */
    private boolean playlistStyle;
}
