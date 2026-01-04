package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeywordExtractionSkill implements Skill {
    
    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${ollama.model}")
    private String model;
    
    @Override
    public boolean execute(PlaylistContext context) {
        try {
            String originalQuery = context.getIntent().getQuery();
            String mode = context.getIntent() != null ? context.getIntent().getMode() : null;
            java.util.Set<String> modeTags = parseModeTags(mode);
            boolean lowCost = modeTags.contains("low_cost");
            log.info("[KeywordExtractionSkill] 原始查询: {} (mode={}, tags={})", originalQuery, mode, modeTags);
                            
            String extractedKeyword;
            if (lowCost) {
                // 低成本模式：跳过 LLM，直接走规则后处理
                log.info("[KeywordExtractionSkill] 低成本模式：跳过 LLM 提取，直接使用规则清洗原始查询");
                extractedKeyword = originalQuery;
            } else {
                extractedKeyword = extractKeyword(originalQuery, context);
            }
                
            if (extractedKeyword != null && !extractedKeyword.isEmpty()) {
                // 使用增强的规则后处理
                String cleaned = applyRuleBasedFallback(extractedKeyword, originalQuery);
                            
                context.getIntent().setQuery(cleaned);
                            
                // 构造关键词数组：按空格分割为数组
                java.util.List<String> keywords = new java.util.ArrayList<>();
                if (!cleaned.isEmpty()) {
                    // 按空格分割为数组，每个元素是一个关键词
                    String[] parts = cleaned.split("\\s+");
                    for (String part : parts) {
                        if (!part.isEmpty()) {
                            keywords.add(part);
                        }
                    }
                }
                            
                // 如果清洗后为空，就退回到原始查询
                if (keywords.isEmpty() && originalQuery != null && !originalQuery.isBlank()) {
                    keywords.add(originalQuery.trim());
                }
                            
                context.setKeywords(keywords);
                context.getIntent().setKeywords(keywords);
                            
                log.info("[KeywordExtractionSkill] 最终关键词数组: {}", keywords);
                return true;
            } else {
                log.warn("[KeywordExtractionSkill] 关键词提取失败,使用原始查询");
                return true;
            }
            
        } catch (Exception e) {
            log.error("[KeywordExtractionSkill] 关键词提取失败", e);
            return true;
        }
    }
    
    private String extractKeyword(String query, PlaylistContext context) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("stream", false);
            
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", getKeywordExtractionPrompt());
            
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", query);
            
            payload.put("messages", List.of(systemMessage, userMessage));
            
            Map<String, Object> response = ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
            
            if (response != null && response.containsKey("message")) {
                Map<String, Object> message = (Map<String, Object>) response.get("message");
                String content = (String) message.get("content");
                
                log.debug("[KeywordExtractionSkill] LLM 原始输出: {}", content);
                
                KeywordResult result = parseKeywordResult(content);
                if (result != null && result.getKeywords() != null && !result.getKeywords().isEmpty()) {
                    log.info("[KeywordExtractionSkill] LLM 理解: {}", result.getReason());
                    log.info("[KeywordExtractionSkill] 提取的关键词数组: {}", result.getKeywords());
                    
                    // 将关键词数组合并为字符串，用于后续处理
                    String extractedKeyword = String.join(" ", result.getKeywords());
                    
                    // 提取数量（如果 LLM 给出）
                    if (result.getCount() != null && result.getCount() > 0) {
                        context.getIntent().setTargetCount(result.getCount());
                        log.info("[KeywordExtractionSkill] 提取到数量: {}", result.getCount());
                    }
                    return extractedKeyword;
                }
                
                // 无法解析结构化 JSON 时，不使用整段输出作为关键词，回退为原始查询
                log.warn("[KeywordExtractionSkill] 无法从 LLM 输出中解析关键词，将回退为原始查询");
                return query;
            }
            
        } catch (Exception e) {
            log.error("[KeywordExtractionSkill] LLM 调用失败", e);
        }
        
        return query;
    }
    
    private String getKeywordExtractionPrompt() {
        return """
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
            """
            .trim();
    }
            
    private java.util.Set<String> parseModeTags(String mode) {
        if (mode == null || mode.isBlank()) {
            return java.util.Collections.emptySet();
        }
        return java.util.Arrays.stream(mode.toLowerCase().split("[,;|+]"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
    }
            
    private KeywordResult parseKeywordResult(String content) {
        try {
            // 方法1：尝试标准JSON解析
            KeywordResult result = tryParseStandardJson(content);
            if (result != null && isValidResult(result)) {
                return result;
            }
            
            // 方法2：正则提取JSON
            KeywordResult regexResult = tryParseWithRegex(content);
            if (regexResult != null && isValidResult(regexResult)) {
                return regexResult;
            }
            
            log.warn("[KeywordExtractionSkill] 所有解析方法失败，LLM响应: {}", content);
            return null;
            
        } catch (Exception e) {
            log.error("[KeywordExtractionSkill] 解析过程异常", e);
            return null;
        }
    }
    
    private KeywordResult tryParseStandardJson(String content) {
        try {
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start >= 0 && end > start) {
                String json = content.substring(start, end + 1);
                return objectMapper.readValue(json, KeywordResult.class);
            }
        } catch (Exception e) {
            log.debug("[KeywordExtractionSkill] 标准JSON解析失败", e);
        }
        return null;
    }
    
    private KeywordResult tryParseWithRegex(String content) {
        try {
            // 使用正则提取JSON字段
            Pattern keywordsPattern = Pattern.compile("\"keywords\"\\s*:\\s*\\[([^\\]]+)\\]");
            Pattern countPattern = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");
            Pattern reasonPattern = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");
            
            Matcher keywordsMatcher = keywordsPattern.matcher(content);
            Matcher countMatcher = countPattern.matcher(content);
            Matcher reasonMatcher = reasonPattern.matcher(content);
            
            if (keywordsMatcher.find()) {
                KeywordResult result = new KeywordResult();
                
                // 解析关键词数组
                String keywordsStr = keywordsMatcher.group(1);
                List<String> keywordsList = new java.util.ArrayList<>();
                // 移除引号和空白，分割成数组
                String[] parts = keywordsStr.split(",");
                for (String part : parts) {
                    String cleaned = part.trim().replaceAll("^\"|\"$", "").trim();
                    if (!cleaned.isEmpty()) {
                        keywordsList.add(cleaned);
                    }
                }
                result.setKeywords(keywordsList);
                
                if (countMatcher.find()) {
                    try {
                        result.setCount(Integer.parseInt(countMatcher.group(1)));
                    } catch (NumberFormatException e) {
                        result.setCount(10); // 默认值
                    }
                } else {
                    result.setCount(10); // 默认值
                }
                
                if (reasonMatcher.find()) {
                    result.setReason(reasonMatcher.group(1).trim());
                } else {
                    result.setReason("正则解析");
                }
                
                return result;
            }
        } catch (Exception e) {
            log.debug("[KeywordExtractionSkill] 正则解析失败", e);
        }
        return null;
    }
    
    private boolean isValidResult(KeywordResult result) {
        if (result == null || result.getKeywords() == null || result.getKeywords().isEmpty()) {
            return false;
        }
        
        // 最小化验证：只检查明显的错误
        for (String keyword : result.getKeywords()) {
            if (keyword == null || keyword.isBlank()) {
                return false;
            }
            
            String kw = keyword.trim();
            
            // 只检查明显的动词（单独出现）
            if (kw.equals("找") || kw.equals("搜索") || kw.equals("播放")) {
                log.warn("[KeywordExtractionSkill] 关键词是纯动词: {}", kw);
                return false;
            }
            
            // 检查长度（放宽到30支持更长的歌名）
            if (kw.length() > 30) {
                log.warn("[KeywordExtractionSkill] 关键词过长 ({} > 30): {}", kw.length(), kw);
                return false;
            }
        }
        
        return true;
    }
    
    private String applyRuleBasedFallback(String rawKeyword, String originalQuery) {
        log.info("[KeywordExtractionSkill] 规则清洗 - 输入: '{}'", rawKeyword);
        String cleaned = rawKeyword.trim();
            
        // 最小化规则：只处理明确的后缀
        // 1. 移除明确的后缀（在末尾）
        String step1 = cleaned.replaceAll("(的歌曲|的歌|的音乐|歌曲|音乐|歌单)$", "");
        if (!step1.equals(cleaned)) {
            log.info("[KeywordExtractionSkill] 步骤1-移除后缀: '{}' -> '{}'", cleaned, step1);
        }
        cleaned = step1;
            
        // 2. 移除明确的数量词（在开头或中间）
        String step2 = cleaned.replaceAll("^[一二三四五六七八九十百千万\\d]+首", "");
        step2 = step2.replaceAll("[一二三四五六七八九十百千万\\d]+首", "");
        if (!step2.equals(cleaned)) {
            log.info("[KeywordExtractionSkill] 步骤2-移除数量词: '{}' -> '{}'", cleaned, step2);
        }
        cleaned = step2;
            
        // 3. 移除明确的动词短语（在开头）- 一次性移除整个短语
        String step3 = cleaned;
        // 先尝试匹配长短语
        if (step3.startsWith("帮我找点")) {
            step3 = step3.substring(4);  // 移除"帮我找点"
        } else if (step3.startsWith("给我找点")) {
            step3 = step3.substring(4);
        } else if (step3.startsWith("帮我找些")) {
            step3 = step3.substring(4);
        } else if (step3.startsWith("给我找些")) {
            step3 = step3.substring(4);
        } else if (step3.startsWith("帮我来点")) {
            step3 = step3.substring(4);
        } else if (step3.startsWith("给我来点")) {
            step3 = step3.substring(4);
        } else if (step3.startsWith("来一份")) {
            step3 = step3.substring(3);
        } else if (step3.startsWith("帮我找")) {
            step3 = step3.substring(3);
        } else if (step3.startsWith("给我找")) {
            step3 = step3.substring(3);
        } else if (step3.startsWith("帮我")) {
            step3 = step3.substring(2);
        } else if (step3.startsWith("给我")) {
            step3 = step3.substring(2);
        } else if (step3.startsWith("来点")) {
            step3 = step3.substring(2);
        } else if (step3.startsWith("搜索")) {
            step3 = step3.substring(2);
        } else if (step3.startsWith("播放")) {
            step3 = step3.substring(2);
        } else if (step3.startsWith("找")) {
            step3 = step3.substring(1);
        }
        if (!step3.equals(cleaned)) {
            log.info("[KeywordExtractionSkill] 步骤3-移除动词: '{}' -> '{}'", cleaned, step3);
        }
        cleaned = step3;
            
        // 4. 智能处理"的"字：只在非专有名词中移除
        // 如果是"XX的YY"结构，且YY不是后缀，则保留
        // 否则转为空格
        if (!cleaned.matches(".*[《》〈〉【】].*")) {  // 不包含书名号
            // 检查是否是"A的B"结构，且B不是后缀
            if (cleaned.matches(".*\\S+的\\S+.*") && !cleaned.matches(".* 的(歌|歌曲|音乐)$")) {
                // 保留结构，只将"的"转为空格
                String step4 = cleaned.replace("的", " ");
                if (!step4.equals(cleaned)) {
                    log.info("[KeywordExtractionSkill] 步骤4-处理的字(保留结构): '{}' -> '{}'", cleaned, step4);
                }
                cleaned = step4;
            } else {
                // 移除所有"的"
                String step4 = cleaned.replace("的", " ");
                if (!step4.equals(cleaned)) {
                    log.info("[KeywordExtractionSkill] 步骤4-处理的字(移除): '{}' -> '{}'", cleaned, step4);
                }
                cleaned = step4;
            }
        }
            
        // 5. 清理多余空格
        String step5 = cleaned.replaceAll("\\s+", " ").trim();
        if (!step5.equals(cleaned)) {
            log.info("[KeywordExtractionSkill] 步骤5-清理空格: '{}' -> '{}'", cleaned, step5);
        }
        cleaned = step5;
            
        // 6. 如果清理后为空或过短，使用原始查询
        if (cleaned.isEmpty() || cleaned.length() < 2) {
            log.warn("[KeywordExtractionSkill] 清理后为空或过短，回退到extractCoreNouns");
            return extractCoreNouns(originalQuery);
        }
            
        log.info("[KeywordExtractionSkill] 规则清洗 - 最终输出: '{}'", cleaned);
        return cleaned;
    }
    
    private String extractCoreNouns(String query) {
        // 简单的规则：移除明显的动词，保留最后的部分
        String cleaned = query;
        String[] verbsToRemove = {"找", "给", "帮", "搜索", "播放", "随便", "推荐"};
        for (String verb : verbsToRemove) {
            cleaned = cleaned.replace(verb, "");
        }
        
        // 移除数量词
        cleaned = cleaned.replaceAll("[零一二三四五六七八九十\\d]+首", "");
        
        // 取最后5个字符作为核心
        cleaned = cleaned.trim();
        if (cleaned.length() > 5) {
            cleaned = cleaned.substring(cleaned.length() - 5);
        }
        
        return cleaned.trim();
    }
    
    @Override
    public String getName() {
        return "KeywordExtractionSkill";
    }
    
    @Data
    private static class KeywordResult {
        @JsonProperty("keywords")
        private List<String> keywords;  // 改为数组
        
        @JsonProperty("entities")  // 新增：结构化实体
        private EntityInfo entities;
        
        @JsonProperty("count")
        private Integer count;
        
        @JsonProperty("reason")
        private String reason;
    }
    
    @Data
    private static class EntityInfo {
        @JsonProperty("singer")
        private String singer;      // 歌手
        
        @JsonProperty("song")
        private String song;        // 歌名
        
        @JsonProperty("album")
        private String album;       // 专辑
        
        @JsonProperty("style")
        private String style;       // 风格
        
        @JsonProperty("scene")
        private String scene;       // 场景
    }
}
