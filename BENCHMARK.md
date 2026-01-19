# Benchmark（离线回归评测）

目标：固定一套离线数据集，在无网络/无外部依赖的情况下重复运行评测，输出“命中率/准确率/耗时”等指标，用于回归检测。

## 快速运行

只跑 benchmark 用例：

```bash
mvn -q -Dtest=OfflineBenchmarkRegressionTest test
```

## 数据集格式

文件：`src/test/resources/benchmark/offline-dataset-v1.jsonl`（JSON Lines，一行一个 case）。

字段说明（简化）：
- `intent`: `query/keywords/artists/mode/singleArtistOnly`
- `candidates[]`: `bvid/title/author/duration/tags/description/relevant`

## 指标

当前回归用例输出：
- `accuracy`: 样本级准确率（relevant 作为正类）
- `hit@K`: 每个 case 的 Top-K 命中率（Top-K 内是否存在 relevant）
- `elapsedMs`: 整体耗时（毫秒）


## 报告/对比

生成量化报告（输出到 `reports/`）：
```bash
mvn -q -Dtest=OfflineBenchmarkReportWriterTest test
```

报告文件：
- `reports/benchmark-latest.json`：最新指标（默认策略）
- `reports/benchmark-compare.json`：default/strict/explore 对比
- `reports/benchmark-latest.md`：可读性总结
