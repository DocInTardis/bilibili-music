package com.example.bilibilimusic.skill;

import com.example.bilibilimusic.context.PlaylistContext;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.BilibiliSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 视频检索能力
 * 对应现有：BilibiliSearchService
 * 职责：根据关键词从 B 站检索视频元数据
 * 📌 确定性能力，不依赖 LLM
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RetrievalSkill implements Skill {
    
    private final BilibiliSearchService searchService;
    
    @Override
    public boolean execute(PlaylistContext context) {
        try {
            log.info("[RetrievalSkill] 开始搜索视频，关键词：{}", context.getIntent().getKeywords() != null && !context.getIntent().getKeywords().isEmpty()
                    ? String.join(" ", context.getIntent().getKeywords())
                    : context.getIntent().getQuery());
            context.setCurrentStage(PlaylistContext.Stage.VIDEO_RETRIEVAL);
            
            String query = context.getIntent().getQuery();
            if (context.getIntent().getKeywords() != null && !context.getIntent().getKeywords().isEmpty()) {
                query = String.join(" ", context.getIntent().getKeywords());
            }
            
            List<VideoInfo> videos = searchService.search(
                query,
                context.getIntent().getLimit()
            );
            
            context.setSearchResults(videos);
            context.setCurrentStage(PlaylistContext.Stage.VIDEO_JUDGEMENT_LOOP);
            
            log.info("[RetrievalSkill] 搜索完成，找到 {} 个视频", videos.size());
            return !videos.isEmpty();
            
        } catch (Exception e) {
            log.error("[RetrievalSkill] 搜索失败", e);
            context.setCurrentStage(PlaylistContext.Stage.FAILED);
            return false;
        }
    }
    
    @Override
    public String getName() {
        return "RetrievalSkill";
    }
}
