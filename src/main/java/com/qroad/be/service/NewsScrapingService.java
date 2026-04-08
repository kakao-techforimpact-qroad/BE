package com.qroad.be.service;

import com.qroad.be.domain.ArticleEntity;
import com.qroad.be.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsScrapingService {

    private final ArticleRepository articleRepository;

    @Value("${news.site.id}")
    private String loginId;

    @Value("${news.site.pw}")
    private String loginPw;

    private static final String BASE_URL = "https://www.okinews.com";
    private static final String LOGIN_ACTION_URL = BASE_URL + "/member/login.php";
    private static final String LIST_URL_PREFIX = BASE_URL + "/news/articleList.html?view_type=sm&page=";

    @Scheduled(cron = "0 0 0 * * MON")
    @Transactional
    public void scrapeNewsWeekly() {
        log.info("Starting weekly news scraping...");
        try {
            // 1. 로그인하여 세션/쿠키 획득
            Map<String, String> cookies = loginAndGetCookies();

            // 2. 페이지 반복하면서 크롤링 (1페이지부터 최대 50페이지 등으로 제한 시 필요)
            int page = 1;
            boolean shouldContinue = true;
            int savedCount = 0;

            while (shouldContinue) {
                log.info("Scraping page: {}", page);
                String listUrl = LIST_URL_PREFIX + page;
                Document listDoc = Jsoup.connect(listUrl)
                        .cookies(cookies)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .get();

                // 리스트 내 기사 링크 추출 (href 속성에 articleView.html?idxno 가 포함된 a 태그 탐색)
                Elements articleLinks = listDoc.select("a[href*=\"articleView.html?idxno=\"]");

                if (articleLinks.isEmpty()) {
                    log.info("No more articles found on page {}. Stopping.", page);
                    break;
                }

                for (Element linkElement : articleLinks) {
                    String relativeLink = linkElement.attr("href");
                    String fullLink;
                    if (relativeLink.startsWith("/")) {
                        fullLink = BASE_URL + relativeLink;
                    } else if (relativeLink.startsWith("http")) {
                        fullLink = relativeLink;
                    } else {
                        fullLink = BASE_URL + "/news/" + relativeLink;
                    }

                    // 중복 검사: DB에 해당 링크가 존재하면, 여기서 전체 반복을 중단 (지난주 스크래핑 내역 도달)
                    if (articleRepository.existsByLink(fullLink)) {
                        log.info("Already scraped article found: {}. Stopping the entire scraping process.", fullLink);
                        shouldContinue = false;
                        break;
                    }

                    try {
                        // 개별 기사 파싱 및 저장
                        ArticleEntity article = parseAndSaveArticle(fullLink, cookies);
                        if (article != null) {
                            savedCount++;
                            // 기사 페이지의 크롤링 부하 방지를 위해 잠시 휴식 (예: 1초)
                            Thread.sleep(1000);
                        }
                    } catch (Exception e) {
                        log.error("Failed to scrape an article at link: {}", fullLink, e);
                    }
                }

                // 다음 페이지로 이동
                page++;
                
                // 만약 너무 무한스크롤로 이어지는 안전장치 (예: 최대 100페이지)
                if (page > 100) {
                    log.warn("Exceeded max pagination limit. Scraped up to page 100.");
                    break;
                }
            }

            log.info("Weekly news scraping finished. Total saved: {}", savedCount);

        } catch (Exception e) {
            log.error("Error occurred during news scraping.", e);
        }
    }



    private Map<String, String> loginAndGetCookies() throws IOException {
        Connection.Response loginResponse = Jsoup.connect(LOGIN_ACTION_URL)
                .method(Connection.Method.POST)
                .data("user_id", loginId)
                .data("user_pw", loginPw)
                // 필요한 경우 hidden값 같은 것 전송 (기본적 ID/PW 방식 기반)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .execute();

        return loginResponse.cookies();
    }

    private ArticleEntity parseAndSaveArticle(String fullLink, Map<String, String> cookies) throws IOException {
        Document articleDoc = Jsoup.connect(fullLink)
                .cookies(cookies)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get();

        // 1. 제목 파싱 (og:title 메타태그에서 추출 시도, 없으면 title 태그)
        String title = "";
        Element ogTitle = articleDoc.selectFirst("meta[property=og:title]");
        if (ogTitle != null) {
            title = ogTitle.attr("content");
        } else {
            title = articleDoc.title();
        }

        // 2. 본문(Contents) 파싱
        // 대부분의 언론사 CMS(N-Soft 등)는 article-view-content-div 등의 id를 사용합니다.
        // 또는 article 태그 내의 p 요소들을 추출.
        String content = "";
        Element contentDiv = articleDoc.selectFirst("#article-view-content-div");
        if (contentDiv != null) {
            content = contentDiv.text();
        } else {
            // id를 못찾을 경우 본문일 확률이 높은 구역의 p 태그들을 사용
            Elements paragraphs = articleDoc.select("div[class*=article] p, p.content");
            if (!paragraphs.isEmpty()) {
                content = paragraphs.text();
            } else {
                content = articleDoc.body().text(); // 최후의 수단
            }
        }

        // 너무 짧은 내용은 로드 에러거나 이상 데이터일 수 있으나 기본적으로 저장 처리
        if (title.isEmpty() && content.isEmpty()) { return null; }

        ArticleEntity articleEntity = ArticleEntity.builder()
                .title(title.length() > 255 ? title.substring(0, 255) : title)
                .content(content)
                .link(fullLink)
                .status("ACTIVE")
                .build();

        return articleRepository.save(articleEntity);
    }
}
