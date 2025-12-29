package com.example.bilibilimusic.service;

import com.example.bilibilimusic.dto.VideoInfo;
import com.microsoft.playwright.*;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class BilibiliSearchService {

    @Value("${bilibili.search-url-template}")
    private String searchUrlTemplate;

    /**
     * 是否使用 headless 模式（false 时会显示浏览器窗口，便于调试和用户查看）
     */
    @Value("${bilibili.headless:true}")
    private boolean headless;

    /**
     * 用于抓取视频详情页（meta keywords / description）
     */
    private final HttpClient httpClient = HttpClient.newHttpClient();

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
            page.navigate(url);

            // 等待页面加载
            page.waitForTimeout(5000);

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
            
            // 使用 Playwright 进入视频详情页，补充抓取标签和简介
            enrichVideoDetailsWithPlaywright(playwright, result);
            
            log.info("最终解析到 {} 个视频", result.size());
        } catch (Exception e) {
            log.error("Playwright 搜索 B 站失败", e);
        }

        return result;
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
