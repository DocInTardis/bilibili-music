package com.example.bilibilimusic.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineBenchmarkRegressionTest {

    @Test
    void offlineDatasetShouldMeetRegressionThresholds() throws Exception {
        OfflineBenchmarkRunner.BenchmarkReport report =
            OfflineBenchmarkRunner.run("benchmark/offline-dataset-v1.jsonl", 5);

        System.out.println("[Benchmark] dataset=" + report.dataset
            + " cases=" + report.caseCount
            + " samples=" + report.totalSamples
            + " accuracy=" + String.format("%.3f", report.accuracy)
            + " hit@"+ report.hitAtK + "=" + String.format("%.3f", report.hitRateAtK)
            + " f1=" + String.format("%.3f", report.f1)
            + " elapsedMs=" + String.format("%.2f", report.elapsedMs)
            + " semanticCalls=" + report.semanticScoreCalls);

        double minAccuracy = readThreshold("OFFLINE_BENCHMARK_MIN_ACCURACY", 0.75);
        double minHitAtK = readThreshold("OFFLINE_BENCHMARK_MIN_HIT_AT_K", 0.75);

        assertTrue(report.caseCount >= 4, "dataset too small for regression");
        assertTrue(report.accuracy >= minAccuracy, "accuracy regression: " + report.accuracy);
        assertTrue(report.hitRateAtK >= minHitAtK, "hit@k regression: " + report.hitRateAtK);
    }

    private double readThreshold(String key, double fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
