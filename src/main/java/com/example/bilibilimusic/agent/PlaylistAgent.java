package com.example.bilibilimusic.agent;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.PlaylistRequest;
import com.example.bilibilimusic.dto.PlaylistResponse;
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
        
        // 2. 阶段一：关键词提取
        log.info("[Stage 1/4] 关键词提取");
        statusCallback.accept("💬 正在理解你的需求...");
        keywordExtractionSkill.execute(context);
        log.info("[Stage 1/4] 提取的关键词：{}", context.getIntent().getQuery());
        
        // 3. 阶段二：检索视频
        log.info("[Stage 2/4] 视频检索");
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
        
        // 4. 阶段三：筛选视频
        log.info("[Stage 3/4] 视频筛选");
        statusCallback.accept("🎵 正在筛选歌单...");
        boolean curateSuccess = curationSkill.execute(context);
        
        if (!curateSuccess) {
            log.warn("[PlaylistAgent] 筛选失败，使用原始结果");
        }
        
        log.info("[Stage 3/4] 筛选完成，保留 {} 个视频", context.getSelectedVideos().size());
        log.info("[Stage 3/4] 筛选理由：{}", context.getSelectionReason());
        context.getSelectedVideos().forEach(v -> 
            log.info("  ✅ {} | {} | {}", v.getTitle(), v.getAuthor(), v.getDuration())
        );
        
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
        
        UserIntent intent = UserIntent.builder()
            .query(request.getQuery())
            .limit(request.getLimit() > 0 ? request.getLimit() : 10)
            .preference(request.getPreference())
            .downloadAsMp3(request.isDownloadAsMp3())
            .build();
        
        context.setIntent(intent);
        context.setCurrentStage(PlaylistContext.Stage.INIT);
        
        return context;
    }
    
    /**
     * 构建响应
     */
    private PlaylistResponse buildResponse(PlaylistContext context) {
        return PlaylistResponse.builder()
            .videos(context.getSelectedVideos())
            .summary(context.getSummary())
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
