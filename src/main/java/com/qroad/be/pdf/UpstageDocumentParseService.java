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
     * 신문 다단 레이아웃에서 Upstage가 elements를 시각적 읽기 순서와 다르게 반환하는 문제를 해결하기 위해
     * 스트림 순서 대신 좌표 기반 귀속(coordinate-based attribution) 방식을 사용합니다.
     *
     * 전략:
     * 1) 제목 요소(heading1 + 대형 paragraph) 수집 후 (page, y) 기준 정렬
     * 2) 각 본문 요소를 y-좌표상 바로 위에 있는 제목(가장 가까운 선행 제목)에 귀속
     *    → Upstage 스트림 순서와 무관하게 올바른 기사에 본문이 배정됨
     */
    public List<PdfArticle> extractArticlesFromElements(List<Map<String, Object>> elements) {
        List<PdfArticle> articles = new ArrayList<>();

        Pattern fontSizePattern = Pattern.compile("font-size:(\\d+)px");
        Pattern tagPattern = Pattern.compile("<[^>]+>");

        // 1차 스캔: heading1 최대 폰트 크기 → h1Threshold 계산
        int maxFontSize = 14;
        for (Map<String, Object> el : elements) {
            if (!"heading1".equals(el.get("category"))) continue;
            String html = getElementHtml(el);
            Matcher fm = fontSizePattern.matcher(html);
            if (fm.find()) maxFontSize = Math.max(maxFontSize, Integer.parseInt(fm.group(1)));
        }
        final int h1Threshold = Math.max(16, (int)(maxFontSize * 0.75));
        log.debug("[Upstage] heading1 최대 폰트 {}px, 제목 기준 {}px 이상", maxFontSize, h1Threshold);

        // 2차 스캔: 제목 요소 수집
        List<TitleEntry> titles = new ArrayList<>();
        for (Map<String, Object> el : elements) {
            String category = (String) el.get("category");
            int page = getPageNum(el);
            String html = getElementHtml(el);
            String text = tagPattern.matcher(html.replaceAll("(?i)<br\\s*/?>", " ")).replaceAll("").trim();

            if (text.isEmpty() || isIgnoredCategory(category)) continue;

            int fontSize = getFontSizeFromHtml(html, fontSizePattern);
            if (isTitleElement(category, fontSize, text, h1Threshold)) {
                double[] b = getCoordBounds(el);
                double y = b != null ? (b[1] + b[3]) / 2 : 0.5;
                double x = b != null ? (b[0] + b[2]) / 2 : 0.5;
                titles.add(new TitleEntry(text, fontSize, page, y, x));
            }
        }

        // 제목을 (page, y) 오름차순 정렬
        titles.sort(Comparator.comparingInt((TitleEntry t) -> t.page).thenComparingDouble(t -> t.y));

        if (titles.isEmpty()) {
            log.warn("[Upstage] 제목 요소를 찾지 못했습니다.");
            return articles;
        }

        // 각 제목별 본문 버퍼 초기화
        List<StringBuilder> bodies = new ArrayList<>(titles.size());
        for (int i = 0; i < titles.size(); i++) bodies.add(new StringBuilder());

        // 3차 스캔: 각 본문 요소를 좌표 기준 가장 가까운 선행 제목에 귀속
        for (Map<String, Object> el : elements) {
            String category = (String) el.get("category");
            int page = getPageNum(el);
            String html = getElementHtml(el);
            String text = tagPattern.matcher(html.replaceAll("(?i)<br\\s*/?>", " ")).replaceAll("").trim();

            if (text.isEmpty() || isIgnoredCategory(category)) continue;

            int fontSize = getFontSizeFromHtml(html, fontSizePattern);
            if (isTitleElement(category, fontSize, text, h1Threshold)) continue;

            double[] b = getCoordBounds(el);
            double elY = b != null ? (b[1] + b[3]) / 2 : 0.5;
            double elX = b != null ? (b[0] + b[2]) / 2 : 0.5;

            int idx = findBestTitle(titles, page, elY, elX);
            if (idx >= 0) bodies.get(idx).append(text).append("\n");
        }

        // 기사 생성 (제목 정렬 순서 유지)
        for (int i = 0; i < titles.size(); i++) {
            saveArticle(articles, titles.get(i).text, bodies.get(i), titles.get(i).page);
        }

        log.info("[Upstage] 기사 {}개 추출 완료", articles.size());
        return articles;
    }

    /**
     * 본문 요소에 대해 가장 적합한 선행 제목 인덱스를 반환합니다.
     *
     * 신문 다단 레이아웃에서 여러 컬럼의 제목이 y-좌표상 교차하기 때문에,
     * 단순 "가장 높은 y" 기준으로 선택하면 잘못된 컬럼의 제목이 선택됩니다.
     *
     * 선택 우선순위:
     * 1) 같은 x-구역(ZONE 이내) 제목 중 가장 최근(높은 page+y) 선택
     * 2) 동일-구역 제목 없으면 x 거리가 가장 가까운 제목 선택 (다단 경계 요소 처리)
     */
    private int findBestTitle(List<TitleEntry> titles, int page, double elY, double elX) {
        final double ZONE = 0.30;

        int bestIdx = -1;
        int bestPage = -1;
        double bestY = -1;
        double bestXDist = Double.MAX_VALUE;
        boolean bestInZone = false;

        for (int i = 0; i < titles.size(); i++) {
            TitleEntry t = titles.get(i);
            if (t.page > page || (t.page == page && t.y > elY)) continue;

            boolean inZone = Math.abs(t.x - elX) <= ZONE;
            double xDist = Math.abs(t.x - elX);

            if (!bestInZone && inZone) {
                bestIdx = i; bestPage = t.page; bestY = t.y; bestXDist = xDist; bestInZone = true;
            } else if (bestInZone && inZone) {
                // 동일-구역 내 여러 제목: x 거리 차이 > 0.05면 가까운 것 우선,
                // 거리 비슷하면 (page, y) 내림차순으로 최신 제목 선택
                if (xDist < bestXDist - 0.05) {
                    bestIdx = i; bestPage = t.page; bestY = t.y; bestXDist = xDist;
                } else if (xDist <= bestXDist + 0.05 &&
                        (t.page > bestPage || (t.page == bestPage && t.y > bestY))) {
                    bestIdx = i; bestPage = t.page; bestY = t.y; bestXDist = xDist;
                }
            } else if (!bestInZone) {
                // x 최근접 우선, x 거리 동점이면 y 높은 것 선택
                if (xDist < bestXDist - 0.01 ||
                        (xDist <= bestXDist + 0.01 && (t.page > bestPage || (t.page == bestPage && t.y > bestY)))) {
                    bestIdx = i; bestPage = t.page; bestY = t.y; bestXDist = xDist;
                }
            }
        }
        return bestIdx;
    }

    private boolean isIgnoredCategory(String category) {
        return "header".equals(category) || "footer".equals(category) || "figure".equals(category);
    }

    private int getPageNum(Map<String, Object> el) {
        return el.get("page") instanceof Number ? ((Number) el.get("page")).intValue() : 1;
    }

    private int getFontSizeFromHtml(String html, Pattern fontSizePattern) {
        Matcher fm = fontSizePattern.matcher(html);
        return fm.find() ? Integer.parseInt(fm.group(1)) : 14;
    }

    private static final Pattern DATE_HEADER_PATTERN = Pattern.compile("\\d{4}년\\d{1,2}월\\d{1,2}일");

    private boolean isTitleElement(String category, int fontSize, String text, int h1Threshold) {
        if ("heading1".equals(category) && fontSize >= h1Threshold) {
            boolean isSentenceEnding = text.endsWith(".") || text.contains("다.");
            boolean hasBullet = text.matches("^[■□●▶▷※○◆◇].*");
            return !isSentenceEnding && !hasBullet;
        }
        if ("paragraph".equals(category) && fontSize >= 20 && text.length() <= 80) {
            // 날짜 헤더(예: "2026년4월24일 금요일")는 제목 아님
            return !DATE_HEADER_PATTERN.matcher(text).find();
        }
        return false;
    }

    private static class TitleEntry {
        final String text;
        final int page;
        final double y, x;

        TitleEntry(String text, int fontSize, int page, double y, double x) {
            this.text = text; this.page = page; this.y = y; this.x = x;
        }
    }

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

    @SuppressWarnings("unchecked")
    private double[] getCoordBounds(Map<String, Object> el) {
        Object coords = el.get("coordinates");
        if (!(coords instanceof List)) return null;
        List<Map<String, Object>> points = (List<Map<String, Object>>) coords;
        if (points.isEmpty()) return null;
        double xMin = 1, yMin = 1, xMax = 0, yMax = 0;
        for (Map<String, Object> pt : points) {
            double x = pt.get("x") instanceof Number ? ((Number) pt.get("x")).doubleValue() : 0;
            double y = pt.get("y") instanceof Number ? ((Number) pt.get("y")).doubleValue() : 0;
            xMin = Math.min(xMin, x); yMin = Math.min(yMin, y);
            xMax = Math.max(xMax, x); yMax = Math.max(yMax, y);
        }
        return new double[]{xMin, yMin, xMax, yMax};
    }
}
