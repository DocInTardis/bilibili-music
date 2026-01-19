package com.example.bilibilimusic.service.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class Mp3DownloadService {

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final ObjectMapper objectMapper;

    @Value("${media.download-dir:downloads}")
    private String downloadDir;

    @Value("${media.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${media.download-timeout-ms:30000}")
    private long downloadTimeoutMs;

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public Mp3DownloadResult downloadMp3(String bvid, String url) {
        String resolvedBvid = normalizeBvid(bvid, url);
        if (resolvedBvid == null || resolvedBvid.isBlank()) {
            throw new IllegalArgumentException("bvid 或 url 不能为空");
        }

        Path target = resolveMp3Path(resolvedBvid);
        ensureDir(target.getParent());

        if (Files.exists(target)) {
            return new Mp3DownloadResult(resolvedBvid, target);
        }

        Object lock = locks.computeIfAbsent(resolvedBvid, key -> new Object());
        synchronized (lock) {
            if (Files.exists(target)) {
                return new Mp3DownloadResult(resolvedBvid, target);
            }
            try {
                String audioUrl = fetchAudioUrl(resolvedBvid);
                if (audioUrl == null || audioUrl.isBlank()) {
                    throw new IllegalStateException("未获取到音频地址");
                }

                Path temp = resolveTempPath(resolvedBvid);
                ensureDir(temp.getParent());
                downloadFile(audioUrl, temp);
                convertToMp3(temp, target);
                Files.deleteIfExists(temp);
                log.info("[MP3] 下载完成 bvid={} -> {}", resolvedBvid, target);
                return new Mp3DownloadResult(resolvedBvid, target);
            } catch (Exception e) {
                throw new IllegalStateException("下载 MP3 失败: " + e.getMessage(), e);
            } finally {
                locks.remove(resolvedBvid);
            }
        }
    }

    public Path resolveMp3Path(String bvid) {
        return Path.of(downloadDir, "mp3", bvid + ".mp3").toAbsolutePath();
    }

    private Path resolveTempPath(String bvid) {
        return Path.of(downloadDir, "tmp", bvid + ".m4s").toAbsolutePath();
    }

    private void ensureDir(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建目录失败: " + dir, e);
        }
    }

    private String normalizeBvid(String bvid, String url) {
        if (bvid != null && !bvid.isBlank()) {
            return bvid.trim();
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("/video/(BV[0-9A-Za-z]+)")
            .matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String fetchAudioUrl(String bvid) throws IOException, InterruptedException {
        long cid = fetchCid(bvid);
        if (cid <= 0L) {
            return null;
        }
        String api = "https://api.bilibili.com/x/player/playurl?bvid=" + encode(bvid)
            + "&cid=" + cid + "&fnval=16";
        HttpResponse<String> response = httpClient().send(buildRequest(api), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("playurl 请求失败: " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.path("data");
        JsonNode dash = data.path("dash");
        String audioUrl = selectBestAudioUrl(dash);
        if (audioUrl != null) {
            return audioUrl;
        }
        JsonNode durl = data.path("durl");
        if (durl.isArray() && durl.size() > 0) {
            return durl.get(0).path("url").asText(null);
        }
        return null;
    }

    private long fetchCid(String bvid) throws IOException, InterruptedException {
        String api = "https://api.bilibili.com/x/web-interface/view?bvid=" + encode(bvid);
        HttpResponse<String> response = httpClient().send(buildRequest(api), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("view 请求失败: " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.path("data");
        return data.path("cid").asLong(0L);
    }

    private String selectBestAudioUrl(JsonNode dash) {
        if (dash == null || dash.isMissingNode()) {
            return null;
        }
        JsonNode audioList = dash.path("audio");
        if (!audioList.isArray() || audioList.isEmpty()) {
            return null;
        }
        long bestBandwidth = -1;
        JsonNode best = null;
        for (JsonNode node : audioList) {
            long bw = node.path("bandwidth").asLong(0L);
            if (bw > bestBandwidth) {
                bestBandwidth = bw;
                best = node;
            }
        }
        if (best == null) {
            best = audioList.get(0);
        }
        String url = best.path("baseUrl").asText(null);
        if (url == null || url.isBlank()) {
            url = best.path("base_url").asText(null);
        }
        return url;
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofMillis(Math.max(5_000L, downloadTimeoutMs)))
            .header("User-Agent", USER_AGENT)
            .build();
        HttpResponse<Path> response = httpClient().send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("音频下载失败: " + response.statusCode());
        }
    }

    private void convertToMp3(Path input, Path output) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.toString());
        cmd.add("-vn");
        cmd.add("-acodec");
        cmd.add("libmp3lame");
        cmd.add("-b:a");
        cmd.add("192k");
        cmd.add(output.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String outputLog = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0 || !Files.exists(output)) {
            throw new IllegalStateException("ffmpeg 转码失败: " + outputLog);
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(5_000L, downloadTimeoutMs)))
            .build();
    }

    private HttpRequest buildRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofMillis(Math.max(5_000L, downloadTimeoutMs)))
            .header("User-Agent", USER_AGENT)
            .build();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Mp3DownloadResult(String bvid, Path file) {
    }
}
