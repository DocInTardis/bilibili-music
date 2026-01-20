package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RerankService {

    @Value("${recommend.rerank.recall.query:2.0}")
    private double recallQueryWeight;

    @Value("${recommend.rerank.recall.keyword:1.5}")
    private double recallKeywordWeight;

    @Value("${recommend.rerank.recall.pref-artist:2.5}")
    private double recallPrefArtistWeight;

    @Value("${recommend.rerank.recall.pref-keyword:2.0}")
    private double recallPrefKeywordWeight;

    @Value("${recommend.rerank.recall.daily-rank:1.0}")
    private double recallDailyWeight;

    @Value("${recommend.rerank.recall.fallback:0.5}")
    private double recallFallbackWeight;

    @Value("${recommend.rerank.recall-scale:1.0}")
    private double recallScale;

    public RerankResult rerank(VideoInfo video, VideoRelevanceScorer.ScoringResult scoringResult) {
        if (video == null) {
            return new RerankResult(0.0, 0.0, 0.0, new LinkedHashMap<>(), "base=0");
        }
        double baseScore = scoringResult != null ? scoringResult.getScore() : 0.0;
        List<String> sources = video.getRecallSources();
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("base", baseScore);
        double recallBoost = computeRecallBoost(sources, breakdown);
        double finalScore = baseScore + recallBoost * recallScale;
        String reason = buildReason(baseScore, recallBoost, sources);
        return new RerankResult(finalScore, baseScore, recallBoost, breakdown, reason);
    }

    private double computeRecallBoost(List<String> sources, Map<String, Double> breakdown) {
        if (sources == null || sources.isEmpty()) {
            return 0.0;
        }
        double boost = 0.0;
        double decay = 1.0;
        for (String source : sources) {
            double w = weightForSource(source);
            if (w == 0.0) {
                continue;
            }
            boost += w * decay;
            breakdown.put("recall." + source, w * decay);
            decay *= 0.7;
        }
        return boost;
    }

    private double weightForSource(String source) {
        if (source == null) {
            return 0.0;
        }
        return switch (source) {
            case MultiRecallService.RECALL_QUERY -> recallQueryWeight;
            case MultiRecallService.RECALL_KEYWORD -> recallKeywordWeight;
            case MultiRecallService.RECALL_PREF_ARTIST -> recallPrefArtistWeight;
            case MultiRecallService.RECALL_PREF_KEYWORD -> recallPrefKeywordWeight;
            case MultiRecallService.RECALL_DAILY_RANK -> recallDailyWeight;
            case MultiRecallService.RECALL_FALLBACK_DB -> recallFallbackWeight;
            default -> 0.0;
        };
    }

    private String buildReason(double baseScore, double recallBoost, List<String> sources) {
        String src = (sources == null || sources.isEmpty()) ? "none" : String.join(",", sources);
        return String.format("base=%.1f recall=%.1f sources=%s", baseScore, recallBoost, src);
    }

    public record RerankResult(double score,
                               double baseScore,
                               double recallBoost,
                               Map<String, Double> breakdown,
                               String reason) {
    }
}
