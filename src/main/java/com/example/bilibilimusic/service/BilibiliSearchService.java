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
            
            log.info("最终解析到 {} 个视频", result.size());
        } catch (Exception e) {
            log.error("Playwright 搜索 B 站失败", e);
        }

        return result;
    }
}
