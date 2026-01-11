package com.example.bilibilimusic.service;

/**
 * 音频指纹识别服务抽象。
 *
 * 通过外部音频指纹 API 对视频音轨进行分析，
 * 返回估算的歌曲数量等信息，用于增强数量估算与 MusicUnit 生成。
 */
public interface AudioFingerprintService {

    /**
     * 根据视频播放地址估算其中包含的歌曲数量。
     *
     * @param videoUrl B 站视频播放地址或可直接拉流的 URL
     * @return 估算的歌曲数量；返回 null 表示未能识别或本次不使用指纹结果
     */
    Integer estimateTrackCount(String videoUrl);
}
