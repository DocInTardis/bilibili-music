package com.example.bilibilimusic.service;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PTQ Prompt 组装配置类
 *
 * 负责将业务字段（用户意图、视频信息等）拼装为 LLM 的用户 Prompt。
 * 后续如果需要做更复杂的模板配置或多版本管理，可以从这里扩展。
 */
@Service
public class PtqPromptService {

    /**
     * 构建歌单总结的用户 Prompt
     */
    public String buildSummaryPrompt(List<VideoInfo> videos,
                                     UserIntent intent,
                                     String selectionReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求：").append(intent.getQuery()).append("\n");
        if (intent.getPreference() != null && !intent.getPreference().isBlank()) {
            sb.append("用户偏好：").append(intent.getPreference()).append("\n");
        }
        if (selectionReason != null && !selectionReason.isBlank()) {
            sb.append("筛选理由：").append(selectionReason).append("\n");
        }
        sb.append("\n已筛选的视频列表：\n");

        for (int i = 0; i < videos.size(); i++) {
            VideoInfo v = videos.get(i);
            sb.append(String.format("%d. %s - %s（%s）\n",
                i + 1, v.getTitle(), v.getAuthor(), v.getDuration()));
        }

        sb.append("\n请生成一段简洁的中文歌单推荐说明（100字以内），包括：\n");
        sb.append("1. 整体风格特点\n");
        sb.append("2. 适合的场景\n");
        sb.append("3. 为什么推荐这些视频\n");

        return sb.toString();
    }

    /**
     * 构建边界视频相关性的判断 Prompt
     */
    public String buildJudgementPrompt(VideoInfo video, UserIntent intent) {
        return String.format(
            "用户需求：%s\n" +
            "关键词：%s\n" +
            "\n视频信息：\n" +
            "标题：%s\n" +
            "作者：%s\n" +
            "时长：%s\n" +
            "\n请判断这个视频是否符合用户需求。\n" +
            "只需回答 'accept' 或 'reject'。",
            intent.getQuery(),
            intent.getKeywords() != null ? String.join(", ", intent.getKeywords()) : "",
            video.getTitle(),
            video.getAuthor(),
            video.getDuration()
        );
    }
}
