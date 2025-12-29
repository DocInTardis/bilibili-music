package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.entity.Conversation;
import com.example.bilibilimusic.service.DatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话窗口管理接口
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private final DatabaseService databaseService;

    /**
     * 获取所有对话窗口
     */
    @GetMapping
    public ResponseEntity<List<Conversation>> listConversations() {
        log.info("[REST API] 获取所有对话窗口");
        List<Conversation> conversations = databaseService.getAllConversations();
        return ResponseEntity.ok(conversations);
    }

    /**
     * 创建新对话窗口
     */
    @PostMapping
    public ResponseEntity<Conversation> createConversation(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        log.info("[REST API] 创建新对话窗口: name={}", name);
        Conversation conversation = databaseService.createNewConversation(name);
        return ResponseEntity.ok(conversation);
    }

    /**
     * 切换到指定对话窗口
     */
    @PutMapping("/{id}/switch")
    public ResponseEntity<Void> switchConversation(@PathVariable Long id) {
        log.info("[REST API] 切换对话窗口: id={}", id);
        databaseService.switchToConversation(id);
        return ResponseEntity.ok().build();
    }
    
    /**
     * 删除对话窗口
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable Long id) {
        log.info("[REST API] 删除对话窗口: id={}", id);
        databaseService.deleteConversation(id);
        return ResponseEntity.ok().build();
    }
}
