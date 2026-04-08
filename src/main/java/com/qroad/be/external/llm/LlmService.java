package com.qroad.be.external.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qroad.be.dto.ArticleChunkDTO;
import com.qroad.be.pdf.PdfArticle;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern REPORTER_EMAIL_PATTERN = Pattern.compile(
            "([\\uAC00-\\uD7A3]{2,4})\\s+([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+)"
    );
    private static final Pattern REPORTER_NAME_ONLY_PATTERN = Pattern.compile(
            "([\\uAC00-\\uD7A3]{2,4})\\s*기자"
    );
    private static final Pattern PDF_ARTICLE_SPLIT = Pattern.compile(
            "={60}\\n\\[기사 \\d+\\][^\\n]*\\n={60}\\n");
    private static final Pattern PDF_TITLE_PATTERN = Pattern.compile("^제목:\\s*(.+)", Pattern.MULTILINE);
    private static final Pattern TITLE_FIELD_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SUMMARY_FIELD_PATTERN = Pattern.compile("\"summary\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CATEGORY_FIELD_PATTERN = Pattern.compile("\"category\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern KEYWORDS_FIELD_PATTERN = Pattern.compile("\"keywords\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);

    // PaperService 하위호환: 원문 문자열 입력도 지원
    public List<ArticleChunkDTO> chunkAndAnalyzePaper(String paperContent) {
        return chunkAndAnalyzePaper(paperContent, null);
    }

    // PaperService 하위호환: 원문 문자열 입력 + 진행률 콜백 지원
    public List<ArticleChunkDTO> chunkAndAnalyzePaper(
            String paperContent,
            BiConsumer<Integer, Integer> progressCallback
    ) {
        List<String> rawArticleContents = chunkByPdfMarkers(paperContent);
        List<PdfArticle> converted = new ArrayList<>();
        int idx = 1;
        for (String rawContent : rawArticleContents) {
            String title = extractTitleFromContent(rawContent);
            String body = extractBodyFromContent(rawContent);
            if (!StringUtils.hasText(body)) {
                continue;
            }
            PdfArticle p = new PdfArticle();
            p.setId("legacy_" + idx++);
            p.setTitle(title);
            p.setText(body);

            Matcher m = REPORTER_EMAIL_PATTERN.matcher(body);
            if (m.find()) {
                p.setReporter(m.group(1));
                p.setEmail(m.group(2));
            }
            converted.add(p);
        }
        return analyzeSeparatedArticles(converted, progressCallback);
    }

    public List<ArticleChunkDTO> analyzeSeparatedArticles(List<PdfArticle> separatedArticles) {
        return analyzeSeparatedArticles(separatedArticles, null);
    }

    public List<ArticleChunkDTO> analyzeSeparatedArticles(
            List<PdfArticle> separatedArticles,
            BiConsumer<Integer, Integer> progressCallback
    ) {
        try {
            List<PdfArticle> sourceArticles = separatedArticles == null ? List.of() : separatedArticles;
            log.info("분리 기사 분석 시작: {}건", sourceArticles.size());

            List<ArticleChunkDTO> articles = new ArrayList<>();
            int total = sourceArticles.size();
            for (int i = 0; i < total; i++) {
                PdfArticle source = sourceArticles.get(i);

                String title = source.getTitle() == null ? "" : source.getTitle().trim();
                String body = source.getText() == null ? "" : source.getText().trim();
                if (body.isEmpty()) {
                    if (progressCallback != null) {
                        progressCallback.accept(i + 1, total);
                    }
                    continue;
                }

                ArticleAnalysisResult analysis = analyzeArticleWithMetadata(body, title);
                String finalTitle = title.isEmpty() ? analysis.getTitle() : title;
                String finalReporter = resolveReporterName(source, body);

                ArticleChunkDTO article = ArticleChunkDTO.builder()
                        .title(finalTitle)
                        .content(body)
                        .reporter(finalReporter)
                        .summary(analysis.getSummary())
                        .keywords(analysis.getKeywords())
                        .category(analysis.getCategory())
                        .build();

                log.info(
                        "기사 메타 확정: index={}/{}, title={}, reporter={}, category={}",
                        i + 1,
                        total,
                        finalTitle,
                        finalReporter,
                        analysis.getCategory()
                );

                articles.add(article);
                if (progressCallback != null) {
                    progressCallback.accept(i + 1, total);
                }
            }

            log.info("분리 기사 분석 완료: {}건", articles.size());
            return articles;

        } catch (Exception e) {
            log.error("분리 기사 분석 중 오류 발생", e);
            throw new RuntimeException("분리 기사 처리 중 오류가 발생했습니다.", e);
        }
    }

    private ArticleAnalysisResult analyzeArticleWithMetadata(String body, String knownTitle) {
        try {
            String analysisPrompt = buildMetadataAnalysisPrompt(body, knownTitle);
            String rawResponse = callGpt4oMini(analysisPrompt);
            String analysisResponse = sanitizeJsonLikeResponse(rawResponse);
            log.info("기사 분석 결과: {}", analysisResponse);

            Map<String, Object> result = parseAnalysisJsonWithFallback(analysisResponse);

            String title = (String) result.getOrDefault("title", "");
            String summary = (String) result.getOrDefault("summary", "");
            @SuppressWarnings("unchecked")
            List<String> keywords = (List<String>) result.getOrDefault("keywords", new ArrayList<>());
            String rawCategory = (String) result.getOrDefault("category", "기타");
            String category = normalizeCategoryValue(rawCategory);

            return new ArticleAnalysisResult(title, summary, keywords, category);

        } catch (Exception e) {
            log.error("기사 분석 중 오류 발생", e);
            return new ArticleAnalysisResult("", "", new ArrayList<>(), "기타");
        }
    }

    private Map<String, Object> parseAnalysisJsonWithFallback(String analysisResponse) {
        try {
            return objectMapper.readValue(analysisResponse, new TypeReference<>() {
            });
        } catch (Exception first) {
            String extracted = extractFirstJsonObject(analysisResponse);
            if (StringUtils.hasText(extracted)) {
                try {
                    return objectMapper.readValue(extracted, new TypeReference<>() {
                    });
                } catch (Exception ignored) {
                    // 아래 복구 단계로 진행
                }
            }

            try {
                String repaired = repairJsonByLlm(analysisResponse);
                return objectMapper.readValue(repaired, new TypeReference<>() {
                });
            } catch (Exception second) {
                log.warn("LLM JSON 파싱 복구 실패, 휴리스틱 파싱으로 대체합니다: {}", second.getMessage());
                return heuristicParseAnalysis(analysisResponse);
            }
        }
    }

    private String sanitizeJsonLikeResponse(String rawResponse) {
        if (rawResponse == null) {
            return "";
        }
        return rawResponse
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .replace('“', '"')
                .replace('”', '"')
                .replace('’', '\'')
                .trim();
    }

    private String extractFirstJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1).trim();
    }

    private String repairJsonByLlm(String brokenJson) {
        String prompt = """
                아래 텍스트를 유효한 JSON 객체 1개로만 고쳐주세요.
                설명, 코드블록, 주석 없이 JSON만 반환하세요.
                키는 반드시 title, summary, keywords, category 를 포함해야 합니다.

                원문:
                %s
                """.formatted(brokenJson);
        return sanitizeJsonLikeResponse(callGpt4oMini(prompt));
    }

    private Map<String, Object> heuristicParseAnalysis(String raw) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("title", extractField(TITLE_FIELD_PATTERN, raw));
        fallback.put("summary", extractField(SUMMARY_FIELD_PATTERN, raw));

        String category = extractField(CATEGORY_FIELD_PATTERN, raw);
        fallback.put("category", StringUtils.hasText(category) ? category : "기타");

        List<String> keywords = extractKeywords(raw);
        fallback.put("keywords", keywords);
        return fallback;
    }

    private String extractField(Pattern pattern, String source) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        Matcher m = pattern.matcher(source);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private List<String> extractKeywords(String source) {
        if (!StringUtils.hasText(source)) {
            return new ArrayList<>();
        }
        Matcher m = KEYWORDS_FIELD_PATTERN.matcher(source);
        if (!m.find()) {
            return new ArrayList<>();
        }
        String inside = m.group(1);
        return Arrays.stream(inside.split(","))
                .map(String::trim)
                .map(s -> s.replaceAll("^\"|\"$", ""))
                .filter(StringUtils::hasText)
                .distinct()
                .limit(5)
                .toList();
    }

    public List<Double> getEmbedding(String text) {
        try {
            com.theokanning.openai.embedding.EmbeddingRequest request = com.theokanning.openai.embedding.EmbeddingRequest
                    .builder()
                    .model("text-embedding-3-small")
                    .input(List.of(text))
                    .build();

            return openAiService.createEmbeddings(request)
                    .getData()
                    .get(0)
                    .getEmbedding();
        } catch (Exception e) {
            log.error("임베딩 생성 중 오류 발생", e);
            throw new RuntimeException("임베딩 생성 실패", e);
        }
    }

    private String callGpt4oMini(String prompt) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(
                        new ChatMessage(ChatMessageRole.SYSTEM.value(),
                                "당신은 신문 기사 분석 전문가입니다. 반드시 JSON만 반환하세요."),
                        new ChatMessage(ChatMessageRole.USER.value(), prompt)))
                .temperature(0.3)
                .maxTokens(4000)
                .build();

        return openAiService.createChatCompletion(request)
                .getChoices()
                .get(0)
                .getMessage()
                .getContent()
                .trim();
    }

    private String buildMetadataAnalysisPrompt(String body, String knownTitle) {
        String titleInstruction = knownTitle.isEmpty()
                ? "1) 기사 제목을 본문에서 추출하세요."
                : String.format("1) title은 반드시 %s 를 그대로 사용하세요.", knownTitle);

        return """
                다음은 신문 기사 본문입니다. 아래 작업을 수행하고 JSON만 반환하세요.

                기사 본문:
                %s

                작업:
                %s
                2) 기사 내용을 2~3문장으로 요약
                3) 핵심 키워드 3개 추출
                4) category는 아래 한글 카테고리 중 하나만 선택
                [기사] 지방 행정, 지역 정치, 공공 기관, 지역 산업, 소상공인 및 시장, 부동산 및 개발, 사건-사고, 시민 사회, 교육 및 보건, 문화-예술, 여행-명소, 생활 스포츠, 지역 인물, 독자 목소리
                [비기사] 광고·홍보, 채용·모집 공고, 입찰·행정 공고

                응답 형식(JSON only):
                {
                  "title": "기사 제목",
                  "summary": "요약문",
                  "keywords": ["키워드1", "키워드2", "키워드3"],
                  "category": "카테고리"
                }
                """.formatted(body, titleInstruction);
    }

    private List<String> chunkByPdfMarkers(String paperContent) {
        String safeContent = paperContent == null ? "" : paperContent;
        String[] blocks = PDF_ARTICLE_SPLIT.split(safeContent);
        List<String> articles = new ArrayList<>();
        for (String block : blocks) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty()) {
                articles.add(trimmed);
            }
        }
        if (articles.isEmpty()) {
            articles.add(safeContent.trim());
        }
        return articles;
    }

    private String extractTitleFromContent(String content) {
        Matcher m = PDF_TITLE_PATTERN.matcher(content == null ? "" : content);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        for (String line : (content == null ? "" : content).split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 3
                    && !trimmed.startsWith("=")
                    && !trimmed.startsWith("[")
                    && !trimmed.startsWith("제목")) {
                return trimmed;
            }
        }
        return "";
    }

    private String extractBodyFromContent(String content) {
        String safe = content == null ? "" : content;
        Matcher m = PDF_TITLE_PATTERN.matcher(safe);
        if (m.find()) {
            return safe.substring(m.end()).trim();
        }
        return safe.trim();
    }

    private String resolveReporterName(PdfArticle source, String body) {
        String sourceReporter = source.getReporter() == null ? "" : source.getReporter().trim();
        String sourceEmail = source.getEmail() == null ? "" : source.getEmail().trim();

        // 이름+이메일이 함께 있을 때만 기자명으로 인정
        if (isValidReporterPair(sourceReporter, sourceEmail)) {
            return sourceReporter;
        }

        Matcher m = REPORTER_EMAIL_PATTERN.matcher(body == null ? "" : body);
        if (m.find()) {
            return m.group(1);
        }

        // 일반 기사에서도 말미에 "홍길동 기자" 형태가 있으면 기자명으로 보완
        String tailNameReporter = extractTailNameOnlyReporter(body);
        if (StringUtils.hasText(tailNameReporter)) {
            return tailNameReporter;
        }

        // 1면 이어짐 기사 예외: 이메일이 누락돼도 "홍길동 기자" 형태면 기자명 허용
        if (isFrontPageContinuation(source, body)) {
            String nameOnlyReporter = extractNameOnlyReporter(body);
            if (StringUtils.hasText(nameOnlyReporter)) {
                return nameOnlyReporter;
            }
        }
        return "";
    }

    private boolean isValidReporterPair(String reporter, String email) {
        if (!StringUtils.hasText(reporter) || !StringUtils.hasText(email)) {
            return false;
        }
        String merged = reporter.trim() + " " + email.trim();
        return REPORTER_EMAIL_PATTERN.matcher(merged).find();
    }

    private boolean isFrontPageContinuation(PdfArticle source, String body) {
        boolean pageBased = source != null
                && source.getPage() == 1
                && source.getContinuationToPage() != null;
        if (pageBased) {
            return true;
        }

        String safeBody = body == null ? "" : body;
        return safeBody.contains("이어짐 기사") || safeBody.contains("▶ 기사");
    }

    private String extractNameOnlyReporter(String body) {
        Matcher matcher = REPORTER_NAME_ONLY_PATTERN.matcher(body == null ? "" : body);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private String extractTailNameOnlyReporter(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String[] lines = body.split("\\R");
        int from = Math.max(0, lines.length - 20);
        StringBuilder tail = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            tail.append(lines[i]).append('\n');
        }
        return extractNameOnlyReporter(tail.toString());
    }

    private String normalizeCategoryValue(String category) {
        if (!StringUtils.hasText(category)) {
            return "기타";
        }
        String c = category.trim();
        return switch (c.toLowerCase()) {
            case "지방 행정", "local-government", "local_government", "local-administration" -> "지방 행정";
            case "지역 정치", "local-politics" -> "지역 정치";
            case "공공 기관", "public-institution" -> "공공 기관";
            case "지역 산업", "regional-industry" -> "지역 산업";
            case "소상공인 및 시장", "small-business-market", "daily-life-market" -> "소상공인 및 시장";
            case "부동산 및 개발", "real-estate-development" -> "부동산 및 개발";
            case "사건-사고", "incidents-accidents", "incident-accident" -> "사건-사고";
            case "시민 사회", "civil-society", "society" -> "시민 사회";
            case "교육 및 보건", "education-health" -> "교육 및 보건";
            case "문화-예술", "culture-arts", "culture-art" -> "문화-예술";
            case "여행-명소", "travel-attractions", "travel-place" -> "여행-명소";
            case "생활 스포츠", "sports-recreation", "sports", "lifestyle" -> "생활 스포츠";
            case "지역 인물", "local-figures", "local-figure" -> "지역 인물";
            case "독자 목소리", "reader-voice", "placeholder", "planning" -> "독자 목소리";
            case "광고·홍보", "ad-promo" -> "광고·홍보";
            case "채용·모집 공고", "hiring-recruitment-notice" -> "채용·모집 공고";
            case "입찰·행정 공고", "bid-admin-notice" -> "입찰·행정 공고";
            default -> c;
        };
    }

    private static class ArticleAnalysisResult {
        private final String title;
        private final String summary;
        private final List<String> keywords;
        private final String category;

        public ArticleAnalysisResult(String title, String summary, List<String> keywords, String category) {
            this.title = title;
            this.summary = summary;
            this.keywords = keywords;
            this.category = category;
        }

        public String getTitle() {
            return title;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public String getCategory() {
            return category;
        }
    }
}
