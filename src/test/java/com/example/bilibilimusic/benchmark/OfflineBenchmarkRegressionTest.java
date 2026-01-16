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

        assertTrue(report.caseCount >= 4, "dataset too small for regression");
        assertTrue(report.accuracy >= 0.85, "accuracy regression: " + report.accuracy);
        assertTrue(report.hitRateAtK >= 0.75, "hit@k regression: " + report.hitRateAtK);
    }
}

