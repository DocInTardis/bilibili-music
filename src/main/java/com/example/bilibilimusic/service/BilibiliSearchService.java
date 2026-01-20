package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.VideoInfo;
import com.microsoft.playwright.*;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class BilibiliSearchService {

    private static final Pattern BVID_PATTERN = Pattern.compile("/video/(BV[0-9A-Za-z]+)", Pattern.CASE_INSENSITIVE);

    private final CacheService cacheService;
    private final VideoDetailCacheService videoDetailCacheService;

    @Value("${bilibili.search-url-template}")
    private String searchUrlTemplate;

    /**
     * 是否使用 headless 模式（false 时会显示浏览器窗口，便于调试和用户查看）
     */
    @Value("${bilibili.headless:true}")
    private boolean headless;
    
    @Value("${bilibili.detail-fetch-concurrency:8}")
    private int detailFetchConcurrency;

    @Value("${bilibili.search-timeout-ms:25000}")
    private long searchTimeoutMs;

    @Value("${bilibili.navigate-timeout-ms:15000}")
    private long navigateTimeoutMs;

    @Value("${bilibili.detail-fetch-timeout-ms:6000}")
    private long detailFetchTimeoutMs;
    
    /**
     * 用于抓取视频详情页（meta keywords / description）
     */
    private HttpClient httpClient;
    
    private ExecutorService detailExecutor;
    
    @PostConstruct
    public void initDetailExecutor() {
        detailExecutor = Executors.newFixedThreadPool(detailFetchConcurrency);
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(1000L, detailFetchTimeoutMs)))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }
    
    @PreDestroy
    public void shutdownDetailExecutor() {
        if (detailExecutor != null) {
            detailExecutor.shutdown();
            try {
                if (!detailExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    detailExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                detailExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
        
    /**
     * 根据单个视频 URL 抓取视频信息（用于手动添加）
     */
    public VideoInfo fetchByUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String bvid = extractBvid(url);
        List<VideoInfo> result = new ArrayList<>();
        result.add(VideoInfo.builder()
            .bvid(bvid)
            .url(url)
            .title("手动添加视频")
            .author("未知")
            .duration("未知")
            .tags("")
            .description("")
            .build());
        VideoDetailCacheService.CacheEntry cachedDb = videoDetailCacheService.findDetail(bvid, url);
        if (cachedDb != null && cachedDb.video() != null) {
            applyCachedDetail(cachedDb.video(), result.get(0));
            if (!cachedDb.stale()) {
                return result.get(0);
            }
        }
        VideoInfo cached = cacheService.getCachedVideoDetail(bvid, url);
        if (cached != null) {
            applyCachedDetail(cached, result.get(0));
            videoDetailCacheService.upsertVideoDetail(result.get(0), cachedDb == null || cachedDb.stale());
            return result.get(0);
        }
        try (Playwright playwright = Playwright.create()) {
            enrichVideoDetailsWithPlaywright(playwright, result);
            if (hasAnyDetail(result.get(0))) {
                cacheService.cacheVideoDetail(result.get(0));
            }
            videoDetailCacheService.upsertVideoDetail(result.get(0), true);
        } catch (Exception e) {
            log.error("Playwright 抓取单个视频详情失败: {}", url, e);
        }
        return result.get(0);
    }
    
    @CircuitBreaker(name = "bilibiliSearch", fallbackMethod = "searchFallback")
    @Retry(name = "bilibiliSearch")
    @RateLimiter(name = "bilibiliSearch")
    @Bulkhead(name = "bilibiliSearch")
    public List<VideoInfo> search(String query, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = searchUrlTemplate.replace("{query}", encoded);

        List<VideoInfo> result = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(headless)
                            .setSlowMo(headless ? 0 : 100)  // 非 headless 模式时放慢操作，便于观察
            );
            Page page = browser.newPage();
            log.info("打开 B 站搜索页面: {}", url);
            page.setDefaultTimeout(searchTimeoutMs);
            page.navigate(url, new Page.NavigateOptions().setTimeout(navigateTimeoutMs));

            // 等待页面加载
            page.waitForTimeout(Math.min(5000, searchTimeoutMs));

            // 尝试多种选择器策略
            List<ElementHandle> cards = page.querySelectorAll("div.bili-video-card__wrap");
            log.info("使用选择器 'div.bili-video-card__wrap' 找到 {} 个卡片", cards.size());
            
            if (cards.isEmpty()) {
                // 备用选择器 1
                cards = page.querySelectorAll(".video-card-common");
                log.info("使用备用选择器 '.video-card-common' 找到 {} 个卡片", cards.size());
            }
            
            if (cards.isEmpty()) {
                // 备用选择器 2
                cards = page.querySelectorAll(".bili-video-card");
                log.info("使用备用选择器 '.bili-video-card' 找到 {} 个卡片", cards.size());
            }

            if (cards.isEmpty()) {
                // 尝试获取所有视频链接
                cards = page.querySelectorAll("a[href*='/video/']");
                log.info("使用通用选择器找到 {} 个视频链接", cards.size());
                
                // 使用简化解析
                for (ElementHandle link : cards) {
                    if (result.size() >= limit) break;
                    try {
                        String href = link.getAttribute("href");
                        String title = link.getAttribute("title");
                        if (title == null || title.isEmpty()) {
                            title = link.innerText().trim();
                        }
                        if (href != null && href.contains("/video/") && !title.isEmpty()) {
                            String finalUrl = href.startsWith("http") ? href : "https:" + href;
                            result.add(VideoInfo.builder()
                                    .bvid(extractBvid(finalUrl))
                                    .title(title)
                                    .url(finalUrl)
                                    .author("未知")
                                    .duration("未知")
                                    .tags("")
                                    .description("")
                                    .build());
                            log.debug("解析到视频: {}", title);
                        }
                    } catch (Exception e) {
                        log.debug("解析链接失败: {}", e.getMessage());
                    }
                }
            } else {
                // 使用原始解析逻辑
                for (ElementHandle card : cards) {
                    if (result.size() >= limit) {
                        break;
                    }
                    try {
                        // 尝试多种标题选择器
                        ElementHandle titleLink = card.querySelector("a.bili-video-card__title");
                        if (titleLink == null) {
                            titleLink = card.querySelector("a[title]");
                        }
                        if (titleLink == null) {
                            titleLink = card.querySelector("a[href*='/video/']");
                        }
                        
                        if (titleLink == null) {
                            log.debug("卡片中未找到标题链接");
                            continue;
                        }
                        
                        String title = titleLink.getAttribute("title");
                        if (title == null || title.isEmpty()) {
                            title = titleLink.innerText().trim();
                        }
                        String href = titleLink.getAttribute("href");
                        String finalUrl = href != null && href.startsWith("http") ? href : "https:" + href;

                        ElementHandle authorSpan = card.querySelector("span.bili-video-card__info--author");
                        if (authorSpan == null) {
                            authorSpan = card.querySelector(".bili-video-card__info--author");
                        }
                        String author = authorSpan != null ? authorSpan.innerText().trim() : "未知";

                        ElementHandle durationSpan = card.querySelector("span.bili-video-card__stats__duration");
                        if (durationSpan == null) {
                            durationSpan = card.querySelector(".duration");
                        }
                        String duration = durationSpan != null ? durationSpan.innerText().trim() : "未知";

                        result.add(VideoInfo.builder()
                                .bvid(extractBvid(finalUrl))
                                .title(title)
                                .url(finalUrl)
                                .author(author)
                                .duration(duration)
                                .tags("")  // TODO: 标签需要点击进视频详情页才能获取
                                .description("")
                                .build());
                        log.debug("成功解析视频: {} - {}", title, author);
                    } catch (Exception e) {
                        log.warn("解析单个视频卡片失败: {}", e.getMessage());
                    }
                }
            }
            
            List<VideoInfo> refreshTargets = new ArrayList<>();
            java.util.Set<String> refreshKeys = new java.util.HashSet<>();
            for (VideoInfo video : result) {
                VideoDetailCacheService.CacheEntry cachedDb = videoDetailCacheService.findDetail(video.getBvid(), video.getUrl());
                if (cachedDb != null && cachedDb.video() != null) {
                    applyCachedDetail(cachedDb.video(), video);
                    if (!cachedDb.stale()) {
                        continue;
                    }
                }
                refreshTargets.add(video);
                refreshKeys.add(buildVideoKey(video));
            }

            if (!refreshTargets.isEmpty()) {
                // 优先使用 HttpClient 并行抓取详情，失败时回退到 Playwright 方案
                try {
                    enrichVideoDetailsWithHttp(refreshTargets);
                } catch (Exception ex) {
                    log.warn("HTTP 抓取视频详情失败，将回退到 Playwright 方案: {}", ex.getMessage());
                    enrichVideoDetailsWithPlaywright(playwright, refreshTargets);
                }
            }
            persistSearchResults(result, refreshKeys);
                        
            log.info("最终解析到 {} 个视频", result.size());
        } catch (Exception e) {
            throw new RuntimeException("bilibili search failed", e);
        }

        return result;
    }

    @SuppressWarnings("unused")
    private List<VideoInfo> searchFallback(String query, int limit, Throwable t) {
        log.warn("[BilibiliSearch] fallback: query={}, limit={}, error={}", query, limit, t != null ? t.getMessage() : "null");
        return java.util.Collections.emptyList();
    }
    
    /**
     * 使用 HttpClient 并行抓取视频详情页，提取标题 / 标签 / 简介 / 播放量 / 评论数
     */
    private void enrichVideoDetailsWithHttp(List<VideoInfo> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (VideoInfo video : videos) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    if (video.getUrl() == null || video.getUrl().isBlank()) {
                        return;
                    }
                    if (!hasAnyDetail(video)) {
                        VideoInfo cached = cacheService.getCachedVideoDetail(video.getBvid(), video.getUrl());
                        if (cached != null) {
                            applyCachedDetail(cached, video);
                            return;
                        }
                    }
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(video.getUrl()))
                        .GET()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .timeout(Duration.ofMillis(Math.max(1000L, detailFetchTimeoutMs)))
                        .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        log.debug("HTTP 抓取详情失败，状态码: {} - {}", response.statusCode(), video.getUrl());
                        return;
                    }
                    String html = response.body();
                    enrichVideoFromHtml(html, video);
                    if (hasAnyDetail(video)) {
                        cacheService.cacheVideoDetail(video);
                    }
                } catch (Exception e) {
                    log.debug("HTTP 抓取视频详情失败: {} - {}", video.getUrl(), e.getMessage());
                }
            }, detailExecutor);
            futures.add(future);
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(detailFetchTimeoutMs, TimeUnit.MILLISECONDS)
                .join();
        } catch (Exception ignored) {
        }
    }
    
    private void enrichVideoFromHtml(String html, VideoInfo video) {
        if (html == null || html.isBlank()) {
            return;
        }
    
        // 1. 标题
        Matcher titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(html);
        if (titleMatcher.find()) {
            String title = titleMatcher.group(1).replaceAll("\\s+", " ").trim();
            if (!title.isBlank()) {
                video.setTitle(title);
            }
        }
    
        // 2. 标签 keywords
        Matcher kwMatcher = Pattern.compile("<meta[^>]+name=['\"]keywords['\"][^>]*content=['\"](.*?)['\"]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(html);
        if (kwMatcher.find()) {
            String keywords = kwMatcher.group(1).trim();
            if (!keywords.isBlank()) {
                video.setTags(keywords);
            }
        }
    
        // 3. 简介 description
        Matcher descMatcher = Pattern.compile("<meta[^>]+name=['\"]description['\"][^>]*content=['\"](.*?)['\"]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(html);
        if (descMatcher.find()) {
            String description = descMatcher.group(1).trim();
            if (!description.isBlank()) {
                video.setDescription(description);
            }
        }
    
        // 4. 播放量 / 评论数
        Long playCount = extractCountFromHtml(html, "播放", "观看");
        if (playCount != null) {
            video.setPlayCount(playCount);
        }
        Long commentCount = extractCountFromHtml(html, "评论", "弹幕");
        if (commentCount != null) {
            video.setCommentCount(commentCount);
        }
    }
    
    private Long extractCountFromHtml(String html, String... keywords) {
        if (html == null || html.isBlank()) {
            return null;
        }
        for (String kw : keywords) {
            try {
                Pattern p = Pattern.compile("(\\d[0-9\\.万亿]*)\\s*" + kw);
                Matcher m = p.matcher(html);
                if (m.find()) {
                    String numText = m.group(1);
                    Long value = parseCountText(numText);
                    if (value != null) {
                        return value;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
    
    /**
     * 使用 Playwright 打开每个视频详情页，提取标题 / 标签 / 简介
     */
    private void enrichVideoDetailsWithPlaywright(Playwright playwright, List<VideoInfo> videos) {
        Browser detailBrowser = null;
        try {
            // 详情抓取始终使用 headless 模式，避免打扰用户
            detailBrowser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            for (VideoInfo video : videos) {
                try {
                    if (video.getUrl() == null || video.getUrl().isBlank()) {
                        continue;
                    }
                    String url = video.getUrl();
                    Page detailPage = detailBrowser.newPage();
                    log.debug("打开视频详情页: {}", url);
                    detailPage.navigate(url);
                    detailPage.waitForTimeout(3000);

                    // 1. 标题：优先使用详情页的 <title>
                    String detailTitle = detailPage.title();
                    if (detailTitle != null && !detailTitle.isBlank()) {
                        video.setTitle(detailTitle);
                    }

                    // 2. 标签：meta[name="keywords"] 或 meta[itemprop="keywords"]
                    String keywords = detailPage.getAttribute("head meta[name='keywords']", "content");
                    if (keywords == null || keywords.isBlank()) {
                        keywords = detailPage.getAttribute("head meta[itemprop='keywords']", "content");
                    }
                    if (keywords != null && !keywords.isBlank()) {
                        video.setTags(keywords);
                    }

                    // 3. 简介：meta[name="description"]
                    String description = detailPage.getAttribute("head meta[name='description']", "content");
                    if (description != null && !description.isBlank()) {
                        video.setDescription(description);
                    }

                    // 4. 播放量：尝试从页面中提取
                    Long playCount = extractPlayCount(detailPage);
                    if (playCount != null) {
                        video.setPlayCount(playCount);
                    }

                    // 5. 评论数：尝试从页面中提取
                    Long commentCount = extractCommentCount(detailPage);
                    if (commentCount != null) {
                        video.setCommentCount(commentCount);
                    }

                    detailPage.close();
                } catch (Exception e) {
                    log.debug("Playwright 抓取视频详情失败: {} - {}", video.getUrl(), e.getMessage());
                }
            }
        } finally {
            if (detailBrowser != null) {
                detailBrowser.close();
            }
        }
    }

    /**
     * 从视频详情页提取播放量
     */
    private Long extractPlayCount(Page page) {
        try {
            // B站播放量可能在多个位置，尝试多种选择器
            ElementHandle playElement = page.querySelector(".view-text");
            if (playElement == null) {
                playElement = page.querySelector(".view-count");
            }
            if (playElement == null) {
                playElement = page.querySelector("[class*='view']");
            }
            
            if (playElement != null) {
                String text = playElement.innerText().trim();
                return parseCountText(text);
            }
            
            // 备用：使用 XPath 在 info 分区找包含 "播放" 的文本
            List<ElementHandle> spans = page.querySelectorAll(".video-info-detail span, .video-data span");
            for (ElementHandle span : spans) {
                String text = span.innerText().trim();
                if (text.contains("播放") || text.contains("观看")) {
                    // 提取数字部分
                    String nextText = text.replaceAll("[^0-9万亿]", "");
                    if (!nextText.isEmpty()) {
                        return parseCountText(nextText);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取播放量失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从视频详情页提取评论数
     */
    private Long extractCommentCount(Page page) {
        try {
            // B站评论数可能在多个位置
            ElementHandle commentElement = page.querySelector(".reply-count");
            if (commentElement == null) {
                commentElement = page.querySelector(".comment-count");
            }
            if (commentElement == null) {
                commentElement = page.querySelector("[class*='comment']");
            }
            
            if (commentElement != null) {
                String text = commentElement.innerText().trim();
                return parseCountText(text);
            }
            
            // 备用：在 info 分区找包含 "评论" 或 "弹幕" 的文本
            List<ElementHandle> spans = page.querySelectorAll(".video-info-detail span, .video-data span");
            for (ElementHandle span : spans) {
                String text = span.innerText().trim();
                if (text.contains("评论")) {
                    String nextText = text.replaceAll("[^0-9万亿]", "");
                    if (!nextText.isEmpty()) {
                        return parseCountText(nextText);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取评论数失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 解析数量文本（支持 "1.2万"、"3.5亿" 等格式）
     */
    private String extractBvid(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher m = BVID_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private boolean hasAnyDetail(VideoInfo video) {
        if (video == null) {
            return false;
        }
        if (video.getTags() != null && !video.getTags().isBlank()) {
            return true;
        }
        if (video.getDescription() != null && !video.getDescription().isBlank()) {
            return true;
        }
        if (video.getPlayCount() != null) {
            return true;
        }
        return video.getCommentCount() != null;
    }

    private void applyCachedDetail(VideoInfo cached, VideoInfo target) {
        if (cached == null || target == null) {
            return;
        }
        if (cached.getTitle() != null && !cached.getTitle().isBlank()) {
            target.setTitle(cached.getTitle());
        }
        if (cached.getTags() != null && !cached.getTags().isBlank()) {
            target.setTags(cached.getTags());
        }
        if (cached.getDescription() != null && !cached.getDescription().isBlank()) {
            target.setDescription(cached.getDescription());
        }
        if (cached.getPlayCount() != null) {
            target.setPlayCount(cached.getPlayCount());
        }
        if (cached.getCommentCount() != null) {
            target.setCommentCount(cached.getCommentCount());
        }
    }

    private void persistSearchResults(List<VideoInfo> videos, java.util.Set<String> refreshKeys) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        java.util.Set<String> keys = refreshKeys != null ? refreshKeys : java.util.Collections.emptySet();
        for (VideoInfo video : videos) {
            boolean refreshAttempted = keys.contains(buildVideoKey(video));
            videoDetailCacheService.upsertVideoDetail(video, refreshAttempted);
        }
    }

    private String buildVideoKey(VideoInfo video) {
        if (video == null) {
            return "";
        }
        if (video.getBvid() != null && !video.getBvid().isBlank()) {
            return "bvid:" + video.getBvid().trim();
        }
        if (video.getUrl() != null && !video.getUrl().isBlank()) {
            return "url:" + video.getUrl().trim();
        }
        return "";
    }

    private Long parseCountText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        
        try {
            text = text.trim().replaceAll("[,\\s]+", "");
            
            // 处理 "万" 和 "亿"
            if (text.contains("亿")) {
                String numPart = text.replace("亿", "");
                double num = Double.parseDouble(numPart);
                return (long) (num * 100_000_000);
            } else if (text.contains("万")) {
                String numPart = text.replace("万", "");
                double num = Double.parseDouble(numPart);
                return (long) (num * 10_000);
            } else {
                // 直接解析数字
                return Long.parseLong(text);
            }
        } catch (Exception e) {
            log.debug("解析数量文本失败: {} - {}", text, e.getMessage());
            return null;
        }
    }
}
