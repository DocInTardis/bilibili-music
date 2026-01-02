package com.example.bilibilimusic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板版本管理服务
 * 
 * 功能：
 * 1. 管理不同节点的 Prompt 模板版本
 * 2. 支持多版本并存，方便 A/B 测试
 * 3. 记录每次 LLM 调用使用的模板版本
 */
@Service
@Slf4j
public class PromptVersionService {
    
    /**
     * Prompt 模板版本存储
     * Key: nodeName
     * Value: 当前使用的版本号
     */
    private final Map<String, String> promptVersions = new ConcurrentHashMap<>();
    
    /**
     * Prompt 模板内容存储
     * Key: nodeName:version
     * Value: 模板内容
     */
    private final Map<String, String> promptTemplates = new ConcurrentHashMap<>();
    
    /**
     * 初始化默认版本
     */
    public PromptVersionService() {
        // 意图理解节点
        registerPrompt("intent_understanding", "v1.0", 
            "分析用户查询意图，提取关键信息...");
        
        // 关键词提取节点
        registerPrompt("keyword_extraction", "v1.2", 
            "从用户查询中提取搜索关键词，只返回核心关键词...");
        
        // 相关性判断节点
        registerPrompt("relevance_decision", "v2.0", 
            "判断视频与用户意图的相关性，使用打分制...");
        
        // 总结生成节点
        registerPrompt("generate_summary", "v1.1", 
            "生成歌单摘要，包含主题、风格、艺人等信息...");
    }
    
    /**
     * 注册 Prompt 模板
     */
    public void registerPrompt(String nodeName, String version, String template) {
        String key = nodeName + ":" + version;
        promptTemplates.put(key, template);
        
        // 更新当前版本
        promptVersions.put(nodeName, version);
        
        log.info("[PromptVersion] 注册模板: node={}, version={}", nodeName, version);
    }
    
    /**
     * 获取当前版本号
     */
    public String getCurrentVersion(String nodeName) {
        return promptVersions.getOrDefault(nodeName, "v1.0");
    }
    
    /**
     * 获取 Prompt 模板
     */
    public String getPromptTemplate(String nodeName) {
        String version = getCurrentVersion(nodeName);
        String key = nodeName + ":" + version;
        return promptTemplates.get(key);
    }
    
    /**
     * 获取指定版本的 Prompt 模板
     */
    public String getPromptTemplate(String nodeName, String version) {
        String key = nodeName + ":" + version;
        return promptTemplates.get(key);
    }
    
    /**
     * 切换版本（用于 A/B 测试）
     */
    public void switchVersion(String nodeName, String version) {
        String key = nodeName + ":" + version;
        if (!promptTemplates.containsKey(key)) {
            log.warn("[PromptVersion] 版本不存在: node={}, version={}", nodeName, version);
            return;
        }
        
        promptVersions.put(nodeName, version);
        log.info("[PromptVersion] 切换版本: node={}, version={}", nodeName, version);
    }
    
    /**
     * 获取所有版本
     */
    public Map<String, String> getAllVersions() {
        return new ConcurrentHashMap<>(promptVersions);
    }
}
