package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.dto.ChatMessage;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.service.PlaylistAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketController {

    private final PlaylistAgentService playlistAgentService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat")
    @SendTo("/topic/messages")
    public ChatMessage handleChatMessage(ChatMessage message) {
        log.info("收到消息: type={}, content={}", message.getType(), message.getContent());

        if ("query".equals(message.getType())) {
            // 发送状态消息：开始处理
            sendStatusMessage("🔍 正在 B 站搜索相关视频...");

            try {
                // 构建请求
                PlaylistRequest request = new PlaylistRequest();
                request.setQuery(message.getContent());
                request.setLimit(message.getLimit() != null ? message.getLimit() : 10);

                // 调用 Agent 生成歌单
                PlaylistResponse response = playlistAgentService.generatePlaylist(request);

                // 发送状态消息：搜索完成
                sendStatusMessage("✅ 搜索完成，正在生成歌单推荐...");

                // 返回结果消息
                return ChatMessage.builder()
                        .type("result")
                        .summary(response.getSummary())
                        .videos(response.getVideos())
                        .build();

            } catch (Exception e) {
                log.error("处理查询失败", e);
                return ChatMessage.builder()
                        .type("error")
                        .content("处理请求时出错：" + e.getMessage())
                        .build();
            }
        }

        return message;
    }

    private void sendStatusMessage(String status) {
        ChatMessage statusMsg = ChatMessage.builder()
                .type("status")
                .content(status)
                .build();
        messagingTemplate.convertAndSend("/topic/messages", statusMsg);
    }
}
