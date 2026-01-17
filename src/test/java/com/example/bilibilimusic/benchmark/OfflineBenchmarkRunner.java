package com.example.bilibilimusic.benchmark;

import com.example.bilibilimusic.context.UserIntent;
import com.example.bilibilimusic.dto.VideoInfo;
import com.example.bilibilimusic.service.UserBehaviorFeedbackService;
import com.example.bilibilimusic.service.UserPreferenceService;
import com.example.bilibilimusic.service.embedding.SemanticRelevanceService;
import com.example.bilibilimusic.skill.VideoRelevanceScorer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class OfflineBenchmarkRunner {

    static BenchmarkReport run(String classpathJsonl, int hitAtK) throws Exception {
        List<DatasetCase> cases = loadJsonl(classpathJsonl);

        UserBehaviorFeedbackService behavior = mock(UserBehaviorFeedbackService.class);
        UserPreferenceService preference = mock(UserPreferenceService.class);

        AtomicInteger semanticCalls = new AtomicInteger();
        SemanticRelevanceService semantic = mock(SemanticRelevanceService.class);
        when(semantic.semanticScore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                semanticCalls.incrementAndGet();
                return 0;
            });

        VideoRelevanceScorer scorer = new VideoRelevanceScorer(behavior, preference, semantic, null);

        long startNs = System.nanoTime();

        int total = 0;
        int correct = 0;
        int tp = 0;
        int fp = 0;
        int fn = 0;

        int hitCount = 0;
        int caseCount = cases.size();

        for (DatasetCase datasetCase : cases) {
            UserIntent intent = toUserIntent(datasetCase.intent);
            List<ScoredLabel> scored = new ArrayList<>();

            for (DatasetVideo v : datasetCase.candidates) {
                VideoInfo video = toVideoInfo(v);
                VideoRelevanceScorer.ScoringResult result = scorer.scoreVideo(video, intent);

                boolean predicted = result.isAccepted();
                boolean actual = v.relevant;

                total++;
                if (predicted == actual) {
                    correct++;
                }
                if (predicted && actual) tp++;
                if (predicted && !actual) fp++;
                if (!predicted && actual) fn++;

                scored.add(new ScoredLabel(v.bvid, result.getScore(), actual));
            }

            scored.sort(Comparator.comparingInt((ScoredLabel s) -> s.score).reversed());
            boolean hit = false;
            for (int i = 0; i < Math.min(hitAtK, scored.size()); i++) {
                if (scored.get(i).relevant) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                hitCount++;
            }
        }

        long elapsedNs = System.nanoTime() - startNs;
        double elapsedMs = elapsedNs / 1_000_000.0;

        double accuracy = total == 0 ? 0.0 : (double) correct / (double) total;
        double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (double) (tp + fp);
        double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (double) (tp + fn);
        double f1 = (precision + recall) == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
        double hitRate = caseCount == 0 ? 0.0 : (double) hitCount / (double) caseCount;

        BenchmarkReport report = new BenchmarkReport();
        report.dataset = classpathJsonl;
        report.caseCount = caseCount;
        report.totalSamples = total;
        report.accuracy = accuracy;
        report.precision = precision;
        report.recall = recall;
        report.f1 = f1;
        report.hitAtK = hitAtK;
        report.hitRateAtK = hitRate;
        report.elapsedMs = elapsedMs;
        report.semanticScoreCalls = semanticCalls.get();
        report.estimatedLlmCalls = 0;
        report.estimatedExternalCostUsd = 0.0;
        return report;
    }

    private static List<DatasetCase> loadJsonl(String classpathJsonl) throws Exception {
        ClassPathResource resource = new ClassPathResource(classpathJsonl);
        String content = resource.getContentAsString(StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        List<DatasetCase> result = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            result.add(mapper.readValue(trimmed, DatasetCase.class));
        }
        return result;
    }

    private static UserIntent toUserIntent(DatasetIntent intent) {
        return UserIntent.builder()
            .query(intent.query)
            .keywords(intent.keywords)
            .artists(intent.artists)
            .mode(intent.mode)
            .singleArtistOnly(intent.singleArtistOnly)
            .targetCount(0)
            .limit(0)
            .build();
    }

    private static VideoInfo toVideoInfo(DatasetVideo v) {
        return VideoInfo.builder()
            .bvid(v.bvid)
            .title(v.title)
            .author(v.author)
            .duration(v.duration)
            .tags(v.tags)
            .description(v.description)
            .playCount(v.playCount)
            .commentCount(v.commentCount)
            .build();
    }

    static final class BenchmarkReport {
        public String dataset;
        public int caseCount;
        public int totalSamples;
        public double accuracy;
        public double precision;
        public double recall;
        public double f1;
        public int hitAtK;
        public double hitRateAtK;
        public double elapsedMs;
        public int semanticScoreCalls;
        public int estimatedLlmCalls;
        public double estimatedExternalCostUsd;
    }

    static final class DatasetCase {
        public String id;
        public DatasetIntent intent;
        public List<DatasetVideo> candidates;
    }

    static final class DatasetIntent {
        public String query;
        public List<String> keywords;
        public List<String> artists;
        public String mode;
        public boolean singleArtistOnly;
    }

    static final class DatasetVideo {
        public String bvid;
        public String title;
        public String author;
        public String duration;
        public String tags;
        public String description;
        public Long playCount;
        public Long commentCount;
        public boolean relevant;
    }

    private record ScoredLabel(String bvid, int score, boolean relevant) {
    }
}
