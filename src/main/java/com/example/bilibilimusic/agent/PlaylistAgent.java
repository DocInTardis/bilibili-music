package com.example.bilibilimusic.agent;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.dto.MusicUnit;
import com.example.bilibilimusic.skill.CurationSkill;
import com.example.bilibilimusic.skill.KeywordExtractionSkill;
import com.example.bilibilimusic.skill.RetrievalSkill;
import com.example.bilibilimusic.skill.SummarySkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * 歌单 Agent - 控制整体流程与决策
 * 
 * PlaylistAgent 不直接执行具体任务，仅负责：
 * - 决定调用哪些 Skill
 * - 控制执行顺序
 * - 判断是否进入下一阶段
 * 
 * 执行流程：
 * Start → SearchVideos → [结果是否为空?] ── Yes → 返回提示
 *                         ↓ No
 *                    CurateVideos
 *                         ↓
 *                    GenerateSummary
 *                         ↓
 *                        End
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaylistAgent {
    
    private final KeywordExtractionSkill keywordExtractionSkill;
    private final RetrievalSkill retrievalSkill;
    private final CurationSkill curationSkill;
    private final SummarySkill summarySkill;
    private final SimpMessagingTemplate messagingTemplate;
    
    /**
     * 执行歌单生成任务
     * @param request 用户请求
     * @param statusCallback 状态回调（用于 WebSocket 推送）
     * @return 歌单响应
     */
    public PlaylistResponse execute(PlaylistRequest request, Consumer<String> statusCallback) {
        log.info("=".repeat(60));
        log.info("[PlaylistAgent] 开始执行任务");
        log.info("[PlaylistAgent] 用户输入：{}", request.getQuery());
        log.info("=".repeat(60));
        
        // 1. 初始化 Context
        PlaylistContext context = initContext(request);

        // 2. 状态：意图理解（当前使用简单规则，后续可接入 LLM）
        context.setCurrentStage(PlaylistContext.Stage.INTENT_UNDERSTANDING);
        statusCallback.accept("🎯 正在理解你的需求...");
        pushIntentUnderstandingUpdate(context);
        
        // 3. 阶段一：关键词提取
        log.info("[Stage 1/4] 关键词提取");
        context.setCurrentStage(PlaylistContext.Stage.KEYWORD_EXTRACTION);
        statusCallback.accept("💬 正在拆解你的需求为可搜索的关键词...");
        keywordExtractionSkill.execute(context);
        log.info("[Stage 1/4] 提取的关键词：{}", context.getIntent().getKeywords());
        // 将意图理解与关键词拆解结果以结构化形式推送给前端
        pushKeywordExtractionUpdate(context);
        
        // 4. 阶段二：检索视频
        log.info("[Stage 2/4] 视频检索");
        context.setCurrentStage(PlaylistContext.Stage.VIDEO_RETRIEVAL);
        statusCallback.accept("🔍 正在搜索视频...");
        boolean searchSuccess = retrievalSkill.execute(context);
        
        // 决策点：搜索结果为空
        if (!searchSuccess || context.getSearchResults().isEmpty()) {
            log.warn("[PlaylistAgent] 搜索无结果，提前结束");
            statusCallback.accept("❌ 未找到相关视频");
            return buildEmptyResponse();
        }
        
        log.info("[Stage 2/4] 搜索成功，找到 {} 个视频", context.getSearchResults().size());
        context.getSearchResults().forEach(v -> 
            log.debug("  - {} | {} | {}", v.getTitle(), v.getAuthor(), v.getDuration())
        );
        
        // 4. 阶段三：视频判断循环（替代整体筛选）
        log.info("[Stage 3/4] 视频逐个判断循环");
        statusCallback.accept("🎵 正在逐个判断哪些视频适合加入歌单...");
        runVideoJudgementLoop(context);
        
        log.info("[Stage 3/4] 判断完成，采纳 {} 个音乐单元", context.getMusicUnits().size());
        
        // 5. 阶段四：生成总结
        log.info("[Stage 4/4] 生成总结");
        statusCallback.accept("📝 正在生成推荐说明...");
        summarySkill.execute(context);
        
        log.info("[Stage 4/4] 生成的总结：{}", context.getSummary());
        log.info("=".repeat(60));
        log.info("[PlaylistAgent] 任务完成");
        log.info("=".repeat(60));
        statusCallback.accept("✅ 歌单生成完成");
        
        // 6. 构建响应
        return buildResponse(context);
    }
    
    /**
     * 初始化 Context
     */
    private PlaylistContext initContext(PlaylistRequest request) {
        PlaylistContext context = new PlaylistContext();
        
        int targetCount = request.getLimit() > 0 ? request.getLimit() : 10;
        int videoLimit = Math.max(targetCount * 2, 20);

        UserIntent intent = UserIntent.builder()
            .query(request.getQuery())
            .targetCount(targetCount)
            .limit(videoLimit)
            .preference(request.getPreference())
            .downloadAsMp3(request.isDownloadAsMp3())
            .build();
        
        context.setIntent(intent);
        context.setCurrentStage(PlaylistContext.Stage.INIT);
        
        return context;
    }
    
    /**
     * 视频逐个判断循环：内容分析 + 数量估算 + 采纳决策 + 流式反馈
     */
    private void runVideoJudgementLoop(PlaylistContext context) {
        java.util.List<VideoInfo> videos = context.getSearchResults();
        if (videos == null || videos.isEmpty()) {
            return;
        }

        // 按优先级排序：1.非合集优先  2.3-5分钟视频权重最高，偏离此区间权重降低
        videos.sort((v1, v2) -> {
            int duration1 = parseDurationToSeconds(v1.getDuration());
            int duration2 = parseDurationToSeconds(v2.getDuration());
            boolean isPlaylist1 = isPlaylistStyle(v1);
            boolean isPlaylist2 = isPlaylistStyle(v2);

            // 先比较是否为合集：非合集优先
            if (isPlaylist1 != isPlaylist2) {
                return isPlaylist1 ? 1 : -1;
            }
            
            // 计算距离最优时长区间（3-5分钟 = 180-300秒）的偏离度
            int optimalMin = 180; // 3分钟
            int optimalMax = 300; // 5分钟
            
            int deviation1 = calculateDeviationFromOptimal(duration1, optimalMin, optimalMax);
            int deviation2 = calculateDeviationFromOptimal(duration2, optimalMin, optimalMax);
            
            // 偏离度小的排在前面（即更接近3-5分钟的视频优先）
            return Integer.compare(deviation1, deviation2);
        });

        UserIntent intent = context.getIntent();
        int targetCount = intent.getTargetCount() > 0 ? intent.getTargetCount() : intent.getLimit();
        int accumulatedCount = 0;

        context.setCurrentStage(PlaylistContext.Stage.VIDEO_JUDGEMENT_LOOP);

        for (VideoInfo video : videos) {
            // 5.1 内容可理解性分析
            context.setCurrentStage(PlaylistContext.Stage.CONTENT_ANALYSIS);
            boolean hasTitle = video.getTitle() != null && !video.getTitle().isBlank();
            boolean hasTags = video.getTags() != null && !video.getTags().isBlank();
            boolean hasDescription = video.getDescription() != null && !video.getDescription().isBlank();
            boolean understandable = hasTitle || hasTags || hasDescription;

            java.util.Map<String, Object> contentAnalysis = new java.util.HashMap<>();
            contentAnalysis.put("hasTitle", hasTitle);
            contentAnalysis.put("hasTags", hasTags);
            contentAnalysis.put("hasDescription", hasDescription);
            contentAnalysis.put("understandable", understandable);

            if (!understandable) {
                context.getTrashVideos().add(video);
                sendStreamUpdate("CONTENT_ANALYSIS", "视频缺少标题/标签/简介，暂存为候选", video, contentAnalysis, null, null, null);
                continue;
            }

            // 5.2 音乐数量估算（对合集视频只计为1首，避免虹高）
            context.setCurrentStage(PlaylistContext.Stage.QUANTITY_ESTIMATION);
            boolean isPlaylist = isPlaylistStyle(video);
            int estimatedCount = isPlaylist ? 1 : estimateSongCount(video);
            java.util.Map<String, Object> quantityEstimation = new java.util.HashMap<>();
            quantityEstimation.put("estimatedCount", estimatedCount);
            quantityEstimation.put("isPlaylist", isPlaylist);
            quantityEstimation.put("method", isPlaylist ? "playlist_treated_as_single" : "approx_by_duration_or_title");

            // 5.3 是否采纳决策
            context.setCurrentStage(PlaylistContext.Stage.CANDIDATE_DECISION);
            boolean accepted = isRelevantToIntent(video, intent);
            String decisionReason = accepted ? "标题/标签与需求较为匹配" : "与需求相关度较低";

            java.util.Map<String, Object> decisionInfo = new java.util.HashMap<>();
            decisionInfo.put("accepted", accepted);
            decisionInfo.put("reason", decisionReason);

            if (accepted) {
                MusicUnit unit = MusicUnit.builder()
                    .title(video.getTitle())
                    .artist(video.getAuthor())
                    .sourceVideo(video)
                    .estimatedCount(estimatedCount)
                    .reason(decisionReason)
                    .playlistStyle(isPlaylistStyle(video))
                    .build();
                context.getMusicUnits().add(unit);
                context.getSelectedVideos().add(video);
                accumulatedCount += estimatedCount;
            } else {
                context.getTrashVideos().add(video);
            }

            // 调试日志：输出本视频的相关性判断细节
            String desc = video.getDescription();
            String descSnippet = desc != null && desc.length() > 80 ? desc.substring(0, 80) + "..." : desc;
            log.info("[Relevance] 标题='{}', tags='{}', desc='{}', keywords={}, accepted={}, reason={}",
                    video.getTitle(),
                    video.getTags(),
                    descSnippet,
                    intent.getKeywords(),
                    accepted,
                    decisionReason);

            // 5.4 流式反馈
            context.setCurrentStage(PlaylistContext.Stage.STREAM_FEEDBACK);
            java.util.Map<String, Object> progress = new java.util.HashMap<>();
            progress.put("accumulatedCount", accumulatedCount);
            progress.put("targetCount", targetCount);

            sendStreamUpdate("VIDEO_JUDGEMENT_LOOP", "已评估一个视频", video, contentAnalysis, quantityEstimation, decisionInfo, progress);

            if (targetCount > 0 && accumulatedCount >= targetCount) {
                break;
            }
        }

        // 目标评估阶段
        context.setCurrentStage(PlaylistContext.Stage.TARGET_EVALUATION);
        int finalCount = accumulatedCount;
        boolean enough = targetCount > 0 && finalCount >= targetCount;

        java.util.Map<String, Object> evalPayload = new java.util.HashMap<>();
        evalPayload.put("targetCount", targetCount);
        evalPayload.put("actualCount", finalCount);
        evalPayload.put("enough", enough);
        evalPayload.put("trashCount", context.getTrashVideos().size());

        com.example.bilibilimusic.dto.ChatMessage evalMsg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stage_update")
            .stage("TARGET_EVALUATION")
            .content(enough ? "已基本满足目标数量" : "未完全达到目标数量，将返回部分结果和相关推荐")
            .payload(evalPayload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", evalMsg);

        if (!enough) {
            context.setCurrentStage(PlaylistContext.Stage.PARTIAL_RESULT);
            context.setSelectionReason(String.format("仅找到约 %d 首，未达到目标 %d 首，已返回部分结果和相关推荐。", finalCount, targetCount));
        } else {
            context.setSelectionReason(String.format("基于视频标题和时长估算，共收集约 %d 首歌曲，满足你的需求。", finalCount));
        }
    }

    /**
     * 将意图理解结果推送给前端
     */
    private void pushIntentUnderstandingUpdate(PlaylistContext context) {
        UserIntent intent = context.getIntent();
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("query", intent.getQuery());
        payload.put("targetCount", intent.getTargetCount());
        payload.put("scenario", intent.getScenario());
        payload.put("preference", intent.getPreference());

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stage_update")
            .stage("INTENT_UNDERSTANDING")
            .content("已理解你的大致需求")
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }

    /**
     * 将关键词拆解结果推送给前端
     */
    private void pushKeywordExtractionUpdate(PlaylistContext context) {
        UserIntent intent = context.getIntent();
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("keywords", intent.getKeywords());
        payload.put("effectiveQuery", intent.getQuery());

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stage_update")
            .stage("KEYWORD_EXTRACTION")
            .content("已将你的需求拆解为可搜索的关键词")
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }

    /**
     * 发送单个视频判断过程的流式反馈
     */
    private void sendStreamUpdate(
            String stage,
            String content,
            VideoInfo video,
            java.util.Map<String, Object> contentAnalysis,
            java.util.Map<String, Object> quantityEstimation,
            java.util.Map<String, Object> decisionInfo,
            java.util.Map<String, Object> progress
    ) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();

        if (video != null) {
            java.util.Map<String, Object> v = new java.util.HashMap<>();
            v.put("title", video.getTitle());
            v.put("author", video.getAuthor());
            v.put("duration", video.getDuration());
            v.put("url", video.getUrl());
            payload.put("video", v);
        }
        if (contentAnalysis != null) {
            payload.put("contentAnalysis", contentAnalysis);
        }
        if (quantityEstimation != null) {
            payload.put("quantityEstimation", quantityEstimation);
        }
        if (decisionInfo != null) {
            payload.put("decision", decisionInfo);
        }
        if (progress != null) {
            payload.put("progress", progress);
        }

        com.example.bilibilimusic.dto.ChatMessage msg = com.example.bilibilimusic.dto.ChatMessage.builder()
            .type("stream_update")
            .stage(stage)
            .content(content)
            .payload(payload)
            .build();
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }

    /**
     * 根据视频时长估算包含的歌曲数量
     */
    private int estimateSongCount(VideoInfo video) {
        int seconds = parseDurationToSeconds(video.getDuration());
        if (seconds <= 0) {
            return 1;
        }
        double minutes = seconds / 60.0;
        int approx = (int) Math.max(1, Math.round(minutes / 4.0));
        if (isPlaylistStyle(video) && approx < 3) {
            approx = 3;
        }
        return approx;
    }

    /**
     * 将 B 站的 "HH:MM:SS" 或 "MM:SS" 格式转换为秒
     */
    private int parseDurationToSeconds(String duration) {
        if (duration == null || duration.isBlank()) {
            return 0;
        }
        String[] parts = duration.trim().split(":");
        try {
            if (parts.length == 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int s = Integer.parseInt(parts[2]);
                return h * 3600 + m * 60 + s;
            } else if (parts.length == 2) {
                int m = Integer.parseInt(parts[0]);
                int s = Integer.parseInt(parts[1]);
                return m * 60 + s;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /**
     * 判断视频是否与用户意图相关
     */
    private boolean isRelevantToIntent(VideoInfo video, UserIntent intent) {
        StringBuilder sb = new StringBuilder();
        if (video.getTitle() != null) sb.append(video.getTitle()).append(' ');
        if (video.getTags() != null) sb.append(video.getTags()).append(' ');
        if (video.getDescription() != null) sb.append(video.getDescription()).append(' ');
        if (video.getAuthor() != null) sb.append(video.getAuthor());
        String haystack = sb.toString().toLowerCase();

        java.util.List<String> kws = intent.getKeywords();
        if (kws == null || kws.isEmpty()) {
            if (intent.getQuery() != null && !intent.getQuery().isBlank()) {
                kws = java.util.List.of(intent.getQuery());
            } else {
                return true;
            }
        }

        for (String k : kws) {
            if (k == null || k.isBlank()) continue;
            if (haystack.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断视频是否为“合集 / Playlist / 串烧”风格
     */
    private boolean isPlaylistStyle(VideoInfo video) {
        String title = video.getTitle();
        if (title == null) return false;
        String t = title.toLowerCase();
        return t.contains("合集") || t.contains("歌单") || t.contains("串烧")
                || t.contains("mix") || t.contains("playlist") || t.contains("连播");
    }

    /**
     * 计算视频时长与最优区间（3-5分钟）的偏离度
     * 返回值越小表示越接近最优区间
     */
    private int calculateDeviationFromOptimal(int durationSeconds, int optimalMin, int optimalMax) {
        if (durationSeconds <= 0) {
            return Integer.MAX_VALUE; // 无法解析时长，最低优先级
        }
        if (durationSeconds >= optimalMin && durationSeconds <= optimalMax) {
            return 0; // 在最优区间内，偏离度为0
        }
        if (durationSeconds < optimalMin) {
            return optimalMin - durationSeconds; // 短于3分钟
        }
        return durationSeconds - optimalMax; // 长于5分钟
    }

    /**
     * 构建响应
     */
    private PlaylistResponse buildResponse(PlaylistContext context) {
        // 将 MusicUnit 中的来源视频去重后返回给前端，保持接口兼容
        java.util.LinkedHashMap<String, VideoInfo> uniqueVideos = new java.util.LinkedHashMap<>();
        for (MusicUnit unit : context.getMusicUnits()) {
            if (unit.getSourceVideo() != null && unit.getSourceVideo().getUrl() != null) {
                uniqueVideos.putIfAbsent(unit.getSourceVideo().getUrl(), unit.getSourceVideo());
            }
        }

        return PlaylistResponse.builder()
            .videos(new java.util.ArrayList<>(uniqueVideos.values()))
            .summary(context.getSummary())
            .trashVideos(context.getTrashVideos())
            .mp3Files(Collections.emptyList())
            .build();
    }
    
    /**
     * 构建空响应
     */
    private PlaylistResponse buildEmptyResponse() {
        return PlaylistResponse.builder()
            .videos(Collections.emptyList())
            .summary("未从 B 站搜索到符合条件的视频，请尝试更换关键词。")
            .mp3Files(Collections.emptyList())
            .build();
    }
}
