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
 * 파이썬의 requests.post()를 Java RestTemplate으로 동일하게 구현.
 *
 * POST https://api.upstage.ai/v1/document-digitizer
 * Authorization: Bearer {API_KEY}
 * Body: multipart/form-data
 *   - document: PDF 바이트
 *   - output_formats: ["html"]
 */
@Slf4j
@Service
public class UpstageDocumentParseService {

    private static final String ENDPOINT = "https://api.upstage.ai/v1/document-digitizer";

    @Value("${upstage.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * PDF 바이트를 Upstage API에 전송하고 HTML 결과를 받아옵니다.
     *
     * @param pdfBytes PDF 파일 바이트
     * @return Upstage가 파싱한 HTML 문자열
     */
    public String parseToHtml(byte[] pdfBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);

        // 파이썬의 files={"document": open("file.pdf", "rb")} 에 해당
        ByteArrayResource pdfResource = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() { return "document.pdf"; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("document", pdfResource);
        body.add("output_formats", "[\"html\"]");

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[Upstage] Document Parse API 호출 시작 ({}bytes)", pdfBytes.length);
        ResponseEntity<Map> response = restTemplate.postForEntity(ENDPOINT, request, Map.class);

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new RuntimeException("Upstage API 응답 오류: " + response.getStatusCode());
        }

        // 응답 구조: {"content": {"html": "..."}, ...}
        Map<?, ?> content = (Map<?, ?>) response.getBody().get("content");
        if (content == null || content.get("html") == null) {
            throw new RuntimeException("Upstage API 응답에 html 필드 없음");
        }

        String html = content.get("html").toString();
        log.info("[Upstage] 파싱 완료 (html {}자)", html.length());
        return html;
    }

    /**
     * Upstage HTML 결과에서 기사 목록을 추출합니다.
     * <h1>/<h2> 태그 = 제목, 이후 <p> 태그들 = 본문으로 취급.
     *
     * @param html Upstage가 반환한 HTML
     * @return 기사 목록 (제목 + 본문)
     */
    public List<PdfArticle> extractArticlesFromHtml(String html) {
        List<PdfArticle> articles = new ArrayList<>();

        // <h1> 또는 <h2> 태그를 제목으로 감지
        Pattern titlePattern = Pattern.compile(
                "<h[12][^>]*>(.*?)</h[12]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        // <p> 태그를 본문으로 감지
        Pattern paraPattern = Pattern.compile(
                "<p[^>]*>(.*?)</p>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        // HTML 태그 제거용
        Pattern tagPattern = Pattern.compile("<[^>]+>");

        Matcher titleMatcher = titlePattern.matcher(html);
        List<int[]> titleRanges = new ArrayList<>();
        List<String> titleTexts = new ArrayList<>();

        while (titleMatcher.find()) {
            titleRanges.add(new int[]{titleMatcher.start(), titleMatcher.end()});
            titleTexts.add(tagPattern.matcher(titleMatcher.group(1)).replaceAll("").trim());
        }

        if (titleRanges.isEmpty()) {
            log.warn("[Upstage] HTML에서 제목(h1/h2) 태그를 찾지 못했습니다.");
            return articles;
        }

        // 각 제목 구간의 본문 수집
        for (int i = 0; i < titleRanges.size(); i++) {
            int bodyStart = titleRanges.get(i)[1];
            int bodyEnd = (i + 1 < titleRanges.size()) ? titleRanges.get(i + 1)[0] : html.length();
            String section = html.substring(bodyStart, bodyEnd);

            StringBuilder bodyBuilder = new StringBuilder();
            Matcher paraMatcher = paraPattern.matcher(section);
            while (paraMatcher.find()) {
                String paraText = tagPattern.matcher(paraMatcher.group(1)).replaceAll("").trim();
                if (!paraText.isEmpty()) {
                    bodyBuilder.append(paraText).append("\n");
                }
            }

            String body = bodyBuilder.toString().trim();
            String title = titleTexts.get(i);

            if (title.length() < 3 || body.length() < 120) continue;

            PdfArticle article = new PdfArticle();
            article.setTitle(title);
            article.setText(body);
            articles.add(article);
        }

        log.info("[Upstage] 기사 {}개 추출 완료", articles.size());
        return articles;
    }
}
