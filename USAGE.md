# Bilibili 音乐助手 Agent - 使用指南

## 功能特性

✅ **分屏界面**：左侧嵌入 B 站页面，右侧智能对话框
✅ **智能搜索**：基于 Playwright 自动在 B 站搜索音乐视频
✅ **AI 推荐**：本地 Ollama qwen:7b 模型生成歌单推荐
✅ **实时交互**：WebSocket 实时推送搜索状态

## 快速开始

### 1. 前置要求

- Java 17+
- Maven 3.6+
- Ollama (本地运行 qwen:7b 模型)

### 2. 启动 Ollama

```powershell
# 拉取模型（首次使用）
ollama pull qwen:7b

# 启动 Ollama 服务
ollama serve
```

### 3. 安装 Playwright 浏览器（首次运行）

```powershell
# 在项目目录下执行
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

### 4. 启动应用

```powershell
cd d:\codes\java\bilibili-music
mvn spring-boot:run
```

### 4.1 One-click desktop start (Windows)

- Create Desktop shortcut: `powershell -ExecutionPolicy Bypass -File scripts/windows/Install-Desktop-Shortcut.ps1`
- Or double-click: `scripts/windows/Start-Bilibili-Music.cmd`
- Optional env file: copy `.env.desktop.example` to `.env.desktop`
- Auto-starts Ollama when `OLLAMA_BASE_URL` is localhost (disable with `BILIBILI_MUSIC_OLLAMA_AUTO_START=false`)
- Rebuilds when sources change or repo is dirty; otherwise runs the latest jar
- More docs: `DESKTOP_START.md`

### 5. 打开浏览器

访问：http://localhost:8083

## 使用方法

1. 打开页面后，左侧显示 B 站页面，右侧是对话框
2. 在右侧输入你的需求，例如：
   - "帮我找 10 首适合学习的纯音乐"
   - "来一份日系 ACG 歌单"
   - "推荐一些适合运动的 BGM"
3. 点击发送，Agent 会自动：
   - 在 B 站搜索相关视频
   - 调用 AI 模型分析和推荐
   - 返回歌单结果和推荐说明

## 配置说明

### application.yml

```yaml
ollama:
  base-url: http://localhost:11434  # Ollama 服务地址
  model: qwen:7b                    # 使用的模型

bilibili:
  headless: false  # false=显示浏览器窗口，true=后台运行
```

### 环境变量（推荐）

- `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`
- `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` / `REDIS_PASSWORD`
- `OLLAMA_BASE_URL` / `OLLAMA_MODEL`
- `WS_ALLOWED_ORIGIN_PATTERNS`（生产环境请配置为你的站点域名，避免使用 `*`）
- `WS_CLUSTER_ENABLED` / `WS_CLUSTER_CHANNEL`（多实例部署时启用 Redis Pub/Sub 广播）
- `AUDIO_FP_ENABLED` / `AUDIO_FP_BASE_URL` / `AUDIO_FP_API_KEY`（可选：音频指纹识别）
- `OLLAMA_EMBEDDINGS_ENABLED` / `OLLAMA_EMBED_MODEL`（可选：向量语义检索/打分）

## 技术栈

- **后端**：Spring Boot 3.3, WebSocket
- **浏览器自动化**：Playwright for Java
- **AI 模型**：Ollama (qwen:7b)
- **前端**：HTML + JavaScript + STOMP.js

## 故障排查

### Playwright 浏览器未安装

如果启动时报错找不到浏览器，执行：

```powershell
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

### Ollama 连接失败

确保 Ollama 服务正在运行：

```powershell
ollama serve
```

### WebSocket 连接失败

检查浏览器控制台，确认 WebSocket 连接到 ws://localhost:8083/ws/chat

## 后续扩展

- [ ] 实现真正控制左侧 B 站页面（通过 CDP 协议）
- [ ] 支持播放列表自动创建
- [ ] 本地 MP3 下载功能
- [ ] 多轮对话优化歌单

## MP3 download and resumable playback

The app can fetch the audio stream from Bilibili and convert it to MP3 for resumable playback.

### API
- `POST /api/media/mp3`
  - Body: `{ "bvid": "BV...", "url": "https://www.bilibili.com/video/BV..." }`
  - Response: `{ "bvid": "BV...", "path": "...", "downloadUrl": "/api/media/mp3/BV...", "size": 12345 }`
- `GET /api/media/mp3/{bvid}`: stream MP3 with Range support.

### Requirements
- `ffmpeg` must be available in PATH or configure `FFMPEG_PATH`.
- Download directory: `MEDIA_DOWNLOAD_DIR` (default `downloads`).
