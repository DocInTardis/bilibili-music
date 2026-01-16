package com.example.bilibilimusic.service.websocket;

public interface WsTopicPublisher {
    void send(String destination, Object payload);
}

