package com.qroad.be.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.*;

/**
 * Upstage Document Parse API 호출 서비스.
 *
 * POST https://api.upstage.ai/v1/document-digitization
 * Authorization: Bearer {API_KEY}
 * Body: multipart/form-data
 *   - document: PDF 바이트
 *   - model: document-parse-260128
 *   - output_formats: ["html","text","markdown"]
 *
 * 응답의 elements 배열을 사용해 기사를 추출합니다.
 * elements[i] = { category, content.html, page, coordinates }
 */
@Slf4j
@Service
public class UpstageDocumentParseService {

    private static final String ENDPOINT = "https://api.upstage.ai/v1/document-digitization";

    @Value("${upstage.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * PDF 바이트를 Upstage API에 전송하고 elements 배열을 반환합니다.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parseToElements(byte[] pdfBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);

        ByteArrayResource pdfResource = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() { return "document.pdf"; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("document", pdfResource);
        body.add("model", "document-parse-260128");
        body.add("ocr", "auto");
        body.add("output_formats", "[\"html\",\"text\",\"markdown\"]");
        body.add("coordinates", "true");

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[Upstage] Document Parse API 호출 시작 ({}bytes)", pdfBytes.length);
        ResponseEntity<Map> response = restTemplate.postForEntity(ENDPOINT, request, Map.class);

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new RuntimeException("Upstage API 응답 오류: " + response.getStatusCode());
        }

        List<Map<String, Object>> elements = (List<Map<String, Object>>) response.getBody().get("elements");
        if (elements == null) {
            throw new RuntimeException("Upstage API 응답에 elements 필드 없음");
        }

        log.info("[Upstage] 파싱 완료 (elements {}개)", elements.size());
        return elements;
    }

    /**
     * Upstage elements 배열에서 기사 목록을 추출합니다.
     *
     * 전략:
     * - category='heading1' 이고 폰트 크기 >= threshold → 기사 제목
     * - category='paragraph' 이고 폰트 크기 >= 18px 이고 텍스트 길이 <= 80자 → 제목으로 처리
     * - 나머지 → 본문
     * - 빈 본문 상태에서 다음 제목이 같은 페이지 AND 더 작은 폰트이면 소제목으로 합침
     */
    public List<PdfArticle> extractArticlesFromElements(List<Map<String, Object>> elements) {
        List<PdfArticle> articles = new ArrayList<>();

        Pattern fontSizePattern = Pattern.compile("font-size:(\\d+)px");
        Pattern tagPattern = Pattern.compile("<[^>]+>");

        // 1차 스캔: heading1 최대 폰트 크기 파악
        int maxFontSize = 14;
        for (Map<String, Object> el : elements) {
            if (!"heading1".equals(el.get("category"))) continue;
            String html = getElementHtml(el);
            Matcher fm = fontSizePattern.matcher(html);
            if (fm.find()) maxFontSize = Math.max(maxFontSize, Integer.parseInt(fm.group(1)));
        }
        // heading1 기준 제목 임계값: 최대 폰트의 75% 이상
        final int h1Threshold = Math.max(16, (int)(maxFontSize * 0.75));
        log.debug("[Upstage] heading1 최대 폰트 {}px, 제목 기준 {}px 이상", maxFontSize, h1Threshold);

        // 2차 스캔: elements 순서대로 기사 조립
        String currentTitle = null;
        int currentTitleFontSize = 0;
        int currentTitlePage = 0;
        StringBuilder currentBody = new StringBuilder();

        for (Map<String, Object> el : elements) {
            String category = (String) el.get("category");
            int page = el.get("page") instanceof Number ? ((Number) el.get("page")).intValue() : 1;
            String html = getElementHtml(el);
            String text = tagPattern.matcher(
                    html.replaceAll("(?i)<br\\s*/?>", " ")).replaceAll("").trim();

            if (text.isEmpty()) continue;

            // header/footer/figure → 무시
            if ("header".equals(category) || "footer".equals(category) || "figure".equals(category)) {
                continue;
            }

            int fontSize = 14;
            Matcher fm = fontSizePattern.matcher(html);
            if (fm.find()) fontSize = Integer.parseInt(fm.group(1));

            boolean isTitle = false;

            if ("heading1".equals(category) && fontSize >= h1Threshold) {
                isTitle = true;
            } else if ("paragraph".equals(category) && fontSize >= 20 && text.length() <= 80) {
                // 짧고 큰 폰트(20px 이상)의 paragraph → 제목으로 처리
                // 18px paragraph는 소제목/불릿으로 본문 처리
                isTitle = true;
            }

            if (isTitle) {
                boolean samePageEmptyBody = (currentTitle != null)
                        && currentBody.length() == 0
                        && page == currentTitlePage
                        && fontSize < currentTitleFontSize;

                if (samePageEmptyBody) {
                    // 직전 제목과 같은 페이지, 본문 없음, 더 작은 폰트 → 소제목
                    currentBody.append(text).append("\n");
                } else {
                    // 새 기사 시작
                    if (currentTitle != null) {
                        saveArticle(articles, currentTitle, currentBody, currentTitlePage);
                    }
                    currentTitle = text;
                    currentTitleFontSize = fontSize;
                    currentTitlePage = page;
                    currentBody = new StringBuilder();
                }
            } else {
                // 본문 추가
                if (currentTitle != null) {
                    currentBody.append(text).append("\n");
                }
            }
        }
        // 마지막 기사 저장
        if (currentTitle != null) {
            saveArticle(articles, currentTitle, currentBody, currentTitlePage);
        }

        log.info("[Upstage] 기사 {}개 추출 완료", articles.size());
        return articles;
    }

    @SuppressWarnings("unchecked")
    private String getElementHtml(Map<String, Object> el) {
        Object content = el.get("content");
        if (content instanceof Map) {
            Object html = ((Map<?, ?>) content).get("html");
            if (html != null) return html.toString();
        }
        return "";
    }

    private void saveArticle(List<PdfArticle> articles, String title, StringBuilder body, int page) {
        if (title == null || title.length() < 3) return;
        String bodyText = body.toString().trim();
        if (bodyText.length() < 120) return;
        PdfArticle article = new PdfArticle();
        article.setTitle(title);
        article.setText(bodyText);
        article.setPage(page);
        articles.add(article);
    }
}
