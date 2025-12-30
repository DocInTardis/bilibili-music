package com.example.bilibilimusic.agent.graph.nodes;

import com.example.bilibilimusic.agent.graph.AgentNode;
import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.entity.Video;
import com.example.bilibilimusic.service.DatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 视频接受+持久化+流式推送节点（副作用节点）
 *
 * 职责：
 * - 立即将接受的视频保存到数据库播放列表
 * - 发送 video_accepted WebSocket 消息，让前端实时显示并可立即播放
 * - 不参与决策，只做副作用
 */
@Slf4j
@RequiredArgsConstructor
public class VideoAcceptedNode implements AgentNode {

    private final DatabaseService databaseService;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public NodeResult execute(PlaylistContext state) {
        int index = state.getCurrentVideoIndex();
        if (index < 0 || index >= state.getSearchResults().size()) {
            return NodeResult.success("progress_update");
        }

        VideoInfo video = state.getSearchResults().get(index);
        int accumulatedCount = state.getAccumulatedCount();
        int targetCount = state.getIntent().getTargetCount();

        // 构建摘要
        String summary = String.format("已添加：%s - %s（第%d首）",
            video.getTitle(),
            video.getAuthor() != null ? video.getAuthor() : "未知",
            accumulatedCount);

        // 数据库持久化：保存视频和歌曲到播放列表
        try {
            // 1. 保存或更新视频信息
            Video videoEntity = databaseService.saveOrUpdateVideo(video);

            if (videoEntity != null && state.getPlaylistId() != null) {
                // 2. 添加到播放列表
                databaseService.addMusicToPlaylist(
                    state.getPlaylistId(),
                    video.getTitle(),
                    video.getAuthor() != null ? video.getAuthor() : "未知",
                    videoEntity,
                    summary, // 使用摘要作为加入原因
                    accumulatedCount // 位置
                );
                log.debug("[Database] 已保存视频到数据库: {} - {}", video.getTitle(), video.getAuthor());
            }
        } catch (Exception e) {
            log.error("[Database] 保存视频到数据库失败: {}", e.getMessage(), e);
        }

        // 发送流式结果（video_accepted）
        Map<String, Object> payload = new HashMap<>();
        payload.put("bvid", video.getBvid());
        payload.put("title", video.getTitle());
        payload.put("author", video.getAuthor());
        payload.put("duration", video.getDuration());
        payload.put("progress", String.format("%d/%d", accumulatedCount, targetCount));

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("video_accepted")
            .content(summary)
            .videos(Collections.singletonList(video))
            .payload(payload)
            .build();

        messagingTemplate.convertAndSend("/topic/messages", msg);

        log.info("[流式发送] 立即发送视频：{} - {} （{}/{})",
            video.getTitle(), video.getAuthor(), accumulatedCount, targetCount);

        return NodeResult.success("progress_update");
    }
}
