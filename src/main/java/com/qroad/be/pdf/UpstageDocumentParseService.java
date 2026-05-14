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
            if (isClassifiedAd(text)) continue;

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

        // 이어짐 기사 병합 후처리
        mergeArticleContinuations(articles);

        log.info("[Upstage] 기사 {}개 추출 완료", articles.size());
        return articles;
    }

    /**
     * "▷ N면 '제목' 이어짐 기사" 마커를 감지하여 잘못 귀속된 이어짐 내용을 올바른 기사에 병합합니다.
     * 또한 "▶ 기사 N면 이어짐" 마커를 본문에서 제거합니다.
     *
     * 이어짐 내용 추출 범위: 마커 다음 줄부터 섹션 경계([...], ■, ▶, ▷ 등) 또는
     * 다음 ▷ 마커 직전까지만 포함하여 다른 기사 내용이 섞이지 않도록 합니다.
     */
    private void mergeArticleContinuations(List<PdfArticle> articles) {
        // 제목이 여러 줄에 걸칠 수 있으므로 DOTALL + 비탐욕 매칭 사용
        // 따옴표: U+2018/U+2019(곡선 단), U+201C/U+201D(곡선 쌍), U+0027(직선)
        Pattern contStartPat = Pattern.compile(
            "▷\\s*\\d+면\\s*[‘’’“”\"](.+?)[‘’’“”\"]\\s*이어짐[^\n]*",
            Pattern.DOTALL
        );
        // ▶ 마커: "기사 2,3면" 형태도 처리
        Pattern contEndPat = Pattern.compile("▶\\s*기사\\s*[\\d,·]+면\\s*이어짐");

        // ▷ 마커가 있는 기사(이어짐 페이지 기사)를 원본 기사에 통째로 병합하고 삭제
        // findContEnd로 잘라내면 다단 레이아웃에서 이종 컬럼 내용이 섞이므로,
        // Upstage가 이미 올바르게 추출한 "continuation article" 전체를 활용합니다.
        Set<Integer> toDelete = new java.util.LinkedHashSet<>();

        for (int i = 0; i < articles.size(); i++) {
            String body = articles.get(i).getText();
            Matcher m = contStartPat.matcher(body);

            // 이 기사 안의 모든 ▷ 섹션을 수집
            List<int[]> markerRanges = new ArrayList<>(); // [matchStart, matchEnd] of each ▷ line
            List<String> fragments = new ArrayList<>();
            while (m.find()) {
                markerRanges.add(new int[]{m.start(), m.end()});
                fragments.add(m.group(1).trim());
            }
            if (markerRanges.isEmpty()) continue;

            boolean anyMerged = false;
            for (int j = 0; j < markerRanges.size(); j++) {
                int markerEnd = markerRanges.get(j)[1];
                String fragment = fragments.get(j);

                // ▷ 마커 다음 줄부터 다음 ▷ 마커 직전(또는 body 끝)까지가 이 섹션의 본문
                int lineEnd = body.indexOf('\n', markerEnd);
                int nextMarkerStart = (j + 1 < markerRanges.size())
                    ? markerRanges.get(j + 1)[0]
                    : body.length();
                String contBody = (lineEnd >= 0 && lineEnd + 1 <= nextMarkerStart)
                    ? body.substring(lineEnd + 1, nextMarkerStart).trim()
                    : "";

                int targetIdx = findArticleByTitleFragment(articles, fragment, i);
                if (targetIdx >= 0 && !contBody.isEmpty()) {
                    PdfArticle target = articles.get(targetIdx);
                    target.setText(target.getText() + "\n" + contBody);
                    log.debug("[이어짐 병합] \"{}\" ({}자) → \"{}\"",
                        fragment.substring(0, Math.min(20, fragment.length())),
                        contBody.length(),
                        target.getTitle().substring(0, Math.min(20, target.getTitle().length())));
                    anyMerged = true;
                }
            }
            if (anyMerged) {
                toDelete.add(i);
            }
        }

        // 역순 삭제 (인덱스 유지)
        List<Integer> deleteList = new ArrayList<>(toDelete);
        deleteList.sort(java.util.Collections.reverseOrder());
        for (int idx : deleteList) articles.remove(idx);

        // ▶ 종료 마커 제거
        for (PdfArticle article : articles) {
            article.setText(contEndPat.matcher(article.getText()).replaceAll("").trim());
        }

        // 병합 후 본문이 너무 짧아진 기사 제거
        articles.removeIf(a -> a.getText().trim().length() < 120);
    }

    /**
     * "▷ N면 '제목 일부' 이어짐" 에서 추출한 제목 조각으로 일치하는 기사를 찾습니다.
     * OCR 줄바꿈 노이즈를 무시하기 위해 공백 제거 후 비교합니다.
     */
    private int findArticleByTitleFragment(List<PdfArticle> articles, String rawFragment, int excludeIdx) {
        String norm = rawFragment.replaceAll("\\s+", "");
        if (norm.length() < 2) return -1;

        // [섹션레이블] 접두어 제거한 버전도 준비 (예: "[지방선거 D-40일]도의원..." → "도의원...")
        String normCore = norm.replaceFirst("^\\[.*?\\]", "").trim();

        // 1순위: 제목에 조각이 포함되거나 조각에 제목이 포함
        for (int i = 0; i < articles.size(); i++) {
            if (i == excludeIdx) continue;
            String normTitle = articles.get(i).getTitle().replaceAll("\\s+", "");
            if (normTitle.contains(norm) || norm.contains(normTitle)) return i;
            if (!normCore.isEmpty() && (normTitle.contains(normCore) || normCore.contains(normTitle))) return i;
        }

        // 2순위: 조각(접두어 제거) 앞 4~8자로 제목 prefix 매칭
        for (String candidate : new String[]{norm, normCore}) {
            int prefixLen = Math.min(8, candidate.length());
            if (prefixLen >= 4) {
                String prefix = candidate.substring(0, prefixLen);
                for (int i = 0; i < articles.size(); i++) {
                    if (i == excludeIdx) continue;
                    String normTitle = articles.get(i).getTitle().replaceAll("\\s+", "");
                    if (normTitle.contains(prefix)) return i;
                }
            }
        }

        // 3순위: 본문 앞부분(300자)에서 핵심 조각 검색
        String searchKey = normCore.length() >= 4 ? normCore.substring(0, Math.min(10, normCore.length())) : norm.substring(0, Math.min(10, norm.length()));
        if (searchKey.length() >= 4) {
            for (int i = 0; i < articles.size(); i++) {
                if (i == excludeIdx) continue;
                String body = articles.get(i).getText();
                String normBody = body.substring(0, Math.min(300, body.length())).replaceAll("\\s+", "");
                if (normBody.contains(searchKey)) return i;
            }
        }
        return -1;
    }

    /**
     * 본문 요소에 대해 가장 적합한 선행 제목 인덱스를 반환합니다.
     *
     * 선택 우선순위:
     * 1) 같은 페이지 제목 중 ZONE(0.25) 이내 + 가장 최근(y 내림차순)
     * 2) 같은 페이지 제목이 없으면 직전 페이지 제목 중 x 최근접
     *
     * 이전 설계의 문제: 이전 페이지 제목이 후속 페이지 본문 요소를 흡수하여
     * 서로 다른 기사 내용이 한 기사로 합쳐지는 현상 발생 → 같은 페이지 우선으로 해결.
     */
    private int findBestTitle(List<TitleEntry> titles, int page, double elY, double elX) {
        final double ZONE = 0.25;

        // 1단계: 같은 페이지, element 위에 있는 제목 중 ZONE 이내 + 최근접 y
        int samePageIdx = -1;
        double samePageBestY = -1;
        double samePageBestXDist = Double.MAX_VALUE;
        boolean samePageInZone = false;

        for (int i = 0; i < titles.size(); i++) {
            TitleEntry t = titles.get(i);
            if (t.page != page || t.y > elY) continue;

            boolean inZone = Math.abs(t.x - elX) <= ZONE;
            double xDist = Math.abs(t.x - elX);

            if (!samePageInZone && inZone) {
                samePageIdx = i; samePageBestY = t.y; samePageBestXDist = xDist; samePageInZone = true;
            } else if (samePageInZone && inZone) {
                if (xDist < samePageBestXDist - 0.05) {
                    samePageIdx = i; samePageBestY = t.y; samePageBestXDist = xDist;
                } else if (xDist <= samePageBestXDist + 0.05 && t.y > samePageBestY) {
                    samePageIdx = i; samePageBestY = t.y; samePageBestXDist = xDist;
                }
            } else if (!samePageInZone) {
                if (xDist < samePageBestXDist - 0.01 ||
                        (xDist <= samePageBestXDist + 0.01 && t.y > samePageBestY)) {
                    samePageIdx = i; samePageBestY = t.y; samePageBestXDist = xDist;
                }
            }
        }
        if (samePageIdx >= 0) return samePageIdx;

        // 2단계: 같은 페이지 제목 없음 → 직전 페이지에서 x 최근접 제목 (이어짐 기사 폴백)
        int prevIdx = -1;
        double prevBestXDist = Double.MAX_VALUE;
        int prevBestPage = -1;
        double prevBestY = -1;

        for (int i = 0; i < titles.size(); i++) {
            TitleEntry t = titles.get(i);
            if (t.page >= page) continue;
            double xDist = Math.abs(t.x - elX);
            if (xDist < prevBestXDist - 0.01 ||
                    (xDist <= prevBestXDist + 0.01 &&
                     (t.page > prevBestPage || (t.page == prevBestPage && t.y > prevBestY)))) {
                prevIdx = i; prevBestXDist = xDist; prevBestPage = t.page; prevBestY = t.y;
            }
        }
        return prevIdx;
    }

    private boolean isIgnoredCategory(String category) {
        return "header".equals(category) || "footer".equals(category) || "figure".equals(category);
    }

    // 분류광고 감지 패턴
    private static final Pattern CLASSIFIED_AD_PHONE = Pattern.compile("[☎✆]|\\d{2,3}[-.]\\d{3,4}[-.]\\d{4}");
    private static final Pattern CLASSIFIED_AD_DETAIL = Pattern.compile(
        "㎡|매매가|보증금|월\\s*\\d+만원|즉시입주|전세|방\\d|건평|대지|토지매매|임대|구합니다|아르바이트|홀서빙|급여협의");
    private boolean isClassifiedAd(String text) {
        // ◆로 시작하면 무조건 분류광고 (지역신문에서 ◆는 분류광고 전용 마커)
        if (text.startsWith("◆")) return true;
        // ◆ 항목 다음 줄: 짧은 줄에 전화번호 + 부동산/구인 키워드 조합
        if (text.length() < 120
                && CLASSIFIED_AD_PHONE.matcher(text).find()
                && CLASSIFIED_AD_DETAIL.matcher(text).find()) return true;
        return false;
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
        // ▷ 마커 wrapping된 인용 조각: 닫는 따옴표로 끝나면서 여는 따옴표가 없는 경우만 제목 아님
        // (“지역 연결 체계 강화 필요” 같이 제목 안에 따옴표 포함 시 정상 제목으로 허용)
        if (text.endsWith("\u201D") && !text.contains("\u201C")) return false;
        if (text.endsWith("\u2019") && !text.contains("\u2018")) return false;
        if ("heading1".equals(category) && fontSize >= h1Threshold) {
            boolean isSentenceEnding = text.endsWith(".") || text.contains("다.");
            boolean hasBullet = text.matches("^[■□●▶▷※○◆◇].*");
            return !isSentenceEnding && !hasBullet;
        }
        if ("paragraph".equals(category) && fontSize >= 20 && text.length() <= 80) {
            if (DATE_HEADER_PATTERN.matcher(text).find()) return false;      // 날짜 헤더
            if (text.matches("^[■□●▶▷※○◆◇].*")) return false;              // 불릿/마커 시작
            if (text.matches("^\\[.*\\]$")) return false;                    // [섹션 레이블]
            return true;
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
