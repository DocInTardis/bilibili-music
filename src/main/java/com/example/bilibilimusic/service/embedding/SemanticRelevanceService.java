package com.example.bilibilimusic.service.embedding;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticRelevanceService {

    private final EmbeddingService embeddingService;

    /**
     * 返回语义相似度分数（整数），范围大致 0..5。
     */
    public int semanticScore(UserIntent intent, VideoInfo video) {
        if (intent == null || video == null) {
            return 0;
        }
        String query = intent.getQuery();
        if (query == null || query.isBlank()) {
            return 0;
        }
        String doc = buildDoc(video);
        if (doc.isBlank()) {
            return 0;
        }

        try {
            float[] q = embeddingService.embed(query);
            float[] d = embeddingService.embed(doc);
            if (q == null || d == null || q.length != d.length || q.length == 0) {
                return 0;
            }
            double sim = cosine(q, d);
            if (Double.isNaN(sim) || sim <= 0.0) {
                return 0;
            }
            // 将 (0..1] 映射到 0..5，保守起步
            int score = (int) Math.round(Math.min(1.0, sim) * 5.0);
            return Math.max(0, score);
        } catch (Exception e) {
            log.debug("[Semantic] scoring failed: {}", e.getMessage());
            return 0;
        }
    }

    private String buildDoc(VideoInfo video) {
        StringBuilder sb = new StringBuilder();
        if (video.getTitle() != null) sb.append(video.getTitle()).append(' ');
        if (video.getAuthor() != null) sb.append(video.getAuthor()).append(' ');
        if (video.getTags() != null) sb.append(video.getTags()).append(' ');
        if (video.getDescription() != null) sb.append(video.getDescription());
        return sb.toString().trim();
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0.0 || nb <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}

