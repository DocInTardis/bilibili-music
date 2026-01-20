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
        // 意图理解节点（目前未直接使用 LLM，可作为占位或后续扩展）
        registerPrompt("intent_understanding", "v1.0",
            "用于意图理解阶段的系统提示，可在引入 LLM 意图解析时填写详细模板");
        
        // 关键词提取节点 - 使用 KeywordExtractionSkill 中的 JSON Schema Prompt
        registerPrompt("keyword_extraction", "v1.2",
            """
            你是关键词提取器，**必须严格按照JSON格式输出**。
                
            # 核心任务
                
            从用户输入中提取最简洁的核心实体（歌手、歌名、专辑、风格、场景）。
                
            # 输出格式（必须严格遵守）
                
            ```json
            {
              "keywords": ["关键词1", "关键词2"],
              "entities": {
                "singer": "歌手名",
                "song": "歌名",
                "album": "专辑名",
                "style": "风格",
                "scene": "场景"
              },
              "count": 10,
              "reason": "简短说明"
            }
            ```
                
            **重要**：
            - 只输出JSON，不要任何解释性文字
            - keywords是数组，每个元素是一个核心实体
            - 如果某个entity为空，省略该字段
                
            # 提取原则
                
            想象你是搜索引擎，提取**最小、最精准的搜索词**。
                
            ✅ **保留**：人名、歌名、专辑名、风格词、场景词
            ❌ **忽略**：动词（找、搜索、播放、给我、帮我）、量词（几首、多少）、后缀（的歌、歌曲、音乐）
                
            # 示例
                
            ## 示例1：单一歌手
                
            输入: "帮我找点夜鹿的歌"
                
            输出:
            ```json
            {
              "keywords": ["夜鹿"],
              "entities": {"singer": "夜鹿"},
              "count": 10,
              "reason": "提取歌手名"
            }
            ```
                
            ## 示例2：歌手+专辑
                
            输入: "找五首许嵩的天龙八部的歌"
                
            输出:
            ```json
            {
              "keywords": ["许嵩", "天龙八部"],
              "entities": {"singer": "许嵩", "album": "天龙八部"},
              "count": 5,
              "reason": "提取歌手和专辑"
            }
            ```
                
            ## 示例3：风格+场景
                
            输入: "帮我找3首适合学习的纯音乐"
                
            输出:
            ```json
            {
              "keywords": ["纯音乐", "学习"],
              "entities": {"style": "纯音乐", "scene": "学习"},
              "count": 3,
              "reason": "提取风格和场景"
            }
            ```
                
            ## 示例4：复杂修饰
                
            输入: "帮我随便找些周杰伦的好听的歌"
                
            输出:
            ```json
            {
              "keywords": ["周杰伦"],
              "entities": {"singer": "周杰伦"},
              "count": 10,
              "reason": "提取歌手名，忽略修饰词"
            }
            ```
                
            ## 示例5：保留专有名词中的"的"
                
            输入: "搜索五月天的盛夏的光年"
                
            输出:
            ```json
            {
              "keywords": ["五月天", "盛夏的光年"],
              "entities": {"singer": "五月天", "song": "盛夏的光年"},
              "count": 10,
              "reason": "提取歌手和歌名，保留歌名中的'的'"
            }
            ```
                
            # 现在处理用户输入
                
            **记住**：只输出JSON，不要任何解释！
            """.trim());
        
        // 相关性判断节点 - 使用 CurationSkill 中的系统 Prompt
        registerPrompt("relevance_decision", "v2.0",
            "你是一个视频相关性判断器。\n" +
            "你的任务是判断视频是否符合用户需求。\n" +
            "只能回答 'accept' 或 'reject'，不要有其他内容。");
        
        // 总结生成节点 - 使用 SummarySkill 中的 PTQ 系统 Prompt
        registerPrompt("generate_summary", "v1.1",
            "你是一个音乐推荐助手，善于根据已筛选的 B 站视频生成歌单推荐说明。\n" +
            "你的回答必须：\n" +
            "1. 使用简体中文\n" +
            "2. 简洁明了，不超过 100 字\n" +
            "3. 只基于提供的视频信息，不引入外部知识\n" +
            "4. 直接输出推荐文案，不要额外的格式标记");
        registerPrompt("video_feedback", "v1.0",
            """
            你是音乐偏好分析助手，请严格输出 JSON。
            任务：根据用户对单个视频的评价，提取情绪、强度、艺人、关键词，并给出简短回应。

            输出格式：
            {
              "sentiment": "positive|negative|neutral",
              "intensity": 0.0,
              "artists": ["艺人"],
              "keywords": ["关键词"],
              "reply": "给用户的简短回应"
            }

            约束：
            - 只输出 JSON，不要额外文本
            - intensity 范围 0~1
            - artists/keywords 可为空数组
            """.trim());
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
