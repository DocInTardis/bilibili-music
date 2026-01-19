package com.example.bilibilimusic.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class OfflineBenchmarkReportWriterTest {

    @Test
    void exportBenchmarkReports() throws Exception {
        String dataset = "benchmark/offline-dataset-v1.jsonl";
        int hitAtK = 5;

        OfflineBenchmarkRunner.BenchmarkReport baseline = OfflineBenchmarkRunner.run(dataset, hitAtK);
        OfflineBenchmarkRunner.BenchmarkReport strict = OfflineBenchmarkRunner.runWithModeOverride(dataset, hitAtK, "strict");
        OfflineBenchmarkRunner.BenchmarkReport explore = OfflineBenchmarkRunner.runWithModeOverride(dataset, hitAtK, "explore");

        List<BenchmarkVariant> variants = new ArrayList<>();
        variants.add(BenchmarkVariant.from("default", baseline));
        variants.add(BenchmarkVariant.from("strict", strict));
        variants.add(BenchmarkVariant.from("explore", explore));

        BenchmarkLatestReport latest = new BenchmarkLatestReport();
        latest.dataset = dataset;
        latest.hitAtK = hitAtK;
        latest.generatedAt = Instant.now().toString();
        latest.metrics = BenchmarkMetrics.from(baseline);

        BenchmarkComparisonReport comparison = new BenchmarkComparisonReport();
        comparison.dataset = dataset;
        comparison.hitAtK = hitAtK;
        comparison.generatedAt = latest.generatedAt;
        comparison.variants = variants;

        Path reportsDir = Path.of("reports");
        Files.createDirectories(reportsDir);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        Files.writeString(
            reportsDir.resolve("benchmark-latest.json"),
            mapper.writeValueAsString(latest),
            StandardCharsets.UTF_8
        );

        Files.writeString(
            reportsDir.resolve("benchmark-compare.json"),
            mapper.writeValueAsString(comparison),
            StandardCharsets.UTF_8
        );

        Files.writeString(
            reportsDir.resolve("benchmark-latest.md"),
            buildMarkdown(latest, variants),
            StandardCharsets.UTF_8
        );
    }

    private static String buildMarkdown(BenchmarkLatestReport latest, List<BenchmarkVariant> variants) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Offline Benchmark Report\n\n");
        sb.append("Generated at: ").append(latest.generatedAt).append("\n\n");
        sb.append("Dataset: ").append(latest.dataset).append("\n\n");
        sb.append("Hit@K: ").append(latest.hitAtK).append("\n\n");
        sb.append("| Variant | Accuracy | Hit@K | F1 | Precision | Recall | Elapsed (ms) |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (BenchmarkVariant variant : variants) {
            BenchmarkMetrics m = variant.metrics;
            sb.append("| ")
                .append(variant.name).append(" | ")
                .append(format(m.accuracy)).append(" | ")
                .append(format(m.hitRateAtK)).append(" | ")
                .append(format(m.f1)).append(" | ")
                .append(format(m.precision)).append(" | ")
                .append(format(m.recall)).append(" | ")
                .append(String.format("%.2f", m.elapsedMs)).append(" |\n");
        }
        return sb.toString();
    }

    private static String format(double value) {
        return String.format("%.3f", value);
    }

    static class BenchmarkLatestReport {
        public String dataset;
        public int hitAtK;
        public String generatedAt;
        public BenchmarkMetrics metrics;
    }

    static class BenchmarkComparisonReport {
        public String dataset;
        public int hitAtK;
        public String generatedAt;
        public List<BenchmarkVariant> variants;
    }

    static class BenchmarkVariant {
        public String name;
        public BenchmarkMetrics metrics;

        static BenchmarkVariant from(String name, OfflineBenchmarkRunner.BenchmarkReport report) {
            BenchmarkVariant variant = new BenchmarkVariant();
            variant.name = name;
            variant.metrics = BenchmarkMetrics.from(report);
            return variant;
        }
    }

    static class BenchmarkMetrics {
        public int caseCount;
        public int totalSamples;
        public double accuracy;
        public double precision;
        public double recall;
        public double f1;
        public int hitAtK;
        public double hitRateAtK;
        public double elapsedMs;

        static BenchmarkMetrics from(OfflineBenchmarkRunner.BenchmarkReport report) {
            BenchmarkMetrics metrics = new BenchmarkMetrics();
            metrics.caseCount = report.caseCount;
            metrics.totalSamples = report.totalSamples;
            metrics.accuracy = report.accuracy;
            metrics.precision = report.precision;
            metrics.recall = report.recall;
            metrics.f1 = report.f1;
            metrics.hitAtK = report.hitAtK;
            metrics.hitRateAtK = report.hitRateAtK;
            metrics.elapsedMs = report.elapsedMs;
            return metrics;
        }
    }
}
