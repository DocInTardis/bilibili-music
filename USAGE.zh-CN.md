# Bilibili 音乐助手 Agent - 使用指南

## 功能概览

- Web UI：右侧对话生成歌单，左侧展示 B 站页面
- Agent 流程：检索 → 本地规则/评分 → 边界场景 LLM 判定 → 汇总输出
- 并行化：候选视频预评分并行执行，降低总耗时
- 可观测：Trace/指标/WS 推送关联 traceId/sessionId/executionId/nodeName/promptVersion
- 在线学习闭环：行为反馈 → 样本落库 → 训练模型版本 → A/B → 回滚

## 快速开始（推荐：Docker 启动 MySQL + Redis）

### 1) 启动依赖服务

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/Up.ps1
```

初始化数据库（首次）：

```powershell
Get-Content backup.sql | docker compose -f docker-compose.dev.yml exec -T mysql mysql -uroot -proot bilibili
```

### 2) 启动 Ollama（本地模型）

```powershell
ollama pull qwen:7b
ollama serve
```

### 3) 安装 Playwright 浏览器（仅首次）

```powershell
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

### 4) 启动应用

```powershell
$env:MYSQL_PASSWORD="root"
mvn spring-boot:run
```

打开：`http://localhost:8080`

## 常用环境变量

- MySQL：`MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`
- Redis：`REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` / `REDIS_PASSWORD`
- Ollama：`OLLAMA_BASE_URL` / `OLLAMA_MODEL`
- Online learning：`ONLINE_LEARNING_ENABLED` / `ONLINE_LEARNING_TREATMENT_RATIO` / `ONLINE_LEARNING_TRAINING_ENABLED`

## 相关文档

- 本地开发环境：`DEV_SETUP.md`
- 桌面一键启动（Windows）：`DESKTOP_START.md`
- Desktop launcher: auto-starts Ollama for localhost; disable with `BILIBILI_MUSIC_OLLAMA_AUTO_START=false`
- 可观测（Prometheus/Grafana/OTel）：`OBSERVABILITY.md`


## MP3 下载与可暂停播放

系统支持将 B 站音乐视频音频转为 MP3 并提供可暂停/继续的音频播放能力。

### 前端行为
- 播放列表点击后会触发 MP3 拉取并用 HTML5 Audio 播放，可随时暂停/继续。
- 每条曲目提供“下载”按钮，手动触发 MP3 准备。

### API
- `POST /api/media/mp3`
  - Body: `{ "bvid": "BV...", "url": "https://www.bilibili.com/video/BV..." }`
  - Response: `{ "bvid": "BV...", "path": "...", "downloadUrl": "/api/media/mp3/BV...", "size": 12345 }`
- `GET /api/media/mp3/{bvid}`：以流式方式读取 MP3（支持 Range）。

### 依赖与配置
- 需要本机可执行 `ffmpeg`（或配置 `FFMPEG_PATH`）。
- 下载目录可配置：`MEDIA_DOWNLOAD_DIR`（默认 `downloads`）。
