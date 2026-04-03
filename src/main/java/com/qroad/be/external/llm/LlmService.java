package com.qroad.be.external.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qroad.be.dto.ArticleChunkDTO;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    // PdfExtractorService가 생성하는 기사 구분 마커 패턴
    private static final Pattern PDF_ARTICLE_SPLIT = Pattern.compile(
            "={60}\\n\\[기사 \\d+\\][^\\n]*\\n={60}\\n");
    // "제목: XXX" 레이블에서 제목 추출
    private static final Pattern PDF_TITLE_PATTERN = Pattern.compile("^제목:\\s*(.+)", Pattern.MULTILINE);

    /**
     * 신문 지면 전체 내용을 기사 단위로 청킹하고, 각 기사의 메타데이터와 요약문, 키워드를 추출합니다.
     *
     * @param paperContent 신문 지면 원문 전체
     * @return 청킹된 기사 리스트 (제목, 내용, 기자명, 요약문, 키워드)
     */
    public List<ArticleChunkDTO> chunkAndAnalyzePaper(String paperContent) {
        return chunkAndAnalyzePaper(paperContent, null);
    }

    /**
     * 신문 지면 전체 내용을 기사 단위로 청킹하고, 각 기사의 메타데이터와 요약문, 키워드를 추출합니다.
     *
     * @param paperContent      신문 지면 원문 전체
     * @param progressCallback  처리 기사 수/전체 기사 수를 전달하는 콜백 (nullable)
     * @return 청킹된 기사 리스트 (제목, 내용, 기자명, 요약문, 키워드)
     */
    public List<ArticleChunkDTO> chunkAndAnalyzePaper(
            String paperContent,
            BiConsumer<Integer, Integer> progressCallback
    ) {
        try {
            log.info("신문 지면 청킹 및 분석 시작");

            // 1단계: PDF 마커([기사 N]) 기준으로 기사 분리
            List<String> rawArticleContents = chunkByPdfMarkers(paperContent);

            log.info("총 {}개의 기사 청킹 완료", rawArticleContents.size());

            // 2단계: 각 기사별로 제목 직접 추출 + LLM으로 요약/키워드 추출
            List<ArticleChunkDTO> articles = new ArrayList<>();
            int total = rawArticleContents.size();
            for (int i = 0; i < total; i++) {
                String rawContent = rawArticleContents.get(i);

                // PDF 구조에서 제목 직접 추출 (LLM 없이 확정적으로)
                String title = extractTitleFromContent(rawContent);
                // 본문은 "제목:" 헤더 제거 후 저장
                String body = extractBodyFromContent(rawContent);

                // LLM으로 요약문·기자명·키워드만 추출
                ArticleAnalysisResult analysis = analyzeArticleWithMetadata(body, title);

                // LLM이 title을 더 잘 인식한 경우 사용 (PDF 추출 실패 폴백)
                String finalTitle = title.isEmpty() ? analysis.getTitle() : title;

                ArticleChunkDTO article = ArticleChunkDTO.builder()
                        .title(finalTitle)
                        .content(body)
                        .reporter(analysis.getReporter())
                        .summary(analysis.getSummary())
                        .keywords(analysis.getKeywords())
                        .build();

                articles.add(article);
                if (progressCallback != null) {
                    progressCallback.accept(i + 1, total);
                }
            }

            log.info("총 {}개의 기사 분석 완료", articles.size());
            return articles;

        } catch (Exception e) {
            log.error("신문 지면 청킹 및 분석 중 오류 발생", e);
            throw new RuntimeException("신문 지면 처리 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * PdfExtractorService가 생성한 [기사 N] 마커를 기준으로 기사를 분리합니다.
     * 기자명 패턴 기반 재청킹 대신 PDF 추출 결과의 구조를 그대로 활용합니다.
     */
    private List<String> chunkByPdfMarkers(String paperContent) {
        String[] blocks = PDF_ARTICLE_SPLIT.split(paperContent);
        List<String> articles = new ArrayList<>();
        for (String block : blocks) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty()) {
                articles.add(trimmed);
            }
        }
        if (articles.isEmpty()) {
            log.warn("PDF 마커 패턴이 발견되지 않아 전체를 하나의 기사로 처리합니다.");
            articles.add(paperContent.trim());
        }
        return articles;
    }

    /**
     * "제목: XXX" 레이블에서 제목을 직접 추출합니다.
     * 레이블이 없거나 비어있으면 본문 첫 번째 실질적인 줄을 제목 후보로 반환합니다.
     */
    private String extractTitleFromContent(String content) {
        Matcher m = PDF_TITLE_PATTERN.matcher(content);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        // 폴백: 본문에서 3자 이상인 첫 번째 실질 줄을 제목 후보로 사용
        // "제목:", "=", "[" 으로 시작하는 구조 라인은 제외
        for (String line : content.split("\\n")) {
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

    // 제목 레이블 이후 본문만 반환
    private String extractBodyFromContent(String content) {
        Matcher m = PDF_TITLE_PATTERN.matcher(content);
        if (m.find()) {
            return content.substring(m.end()).trim();
        }
        return content.trim();
    }

    /**
     * 개별 기사의 기자명, 요약문, 키워드를 추출합니다.
     * title은 PDF 구조에서 이미 추출되었으므로 폴백용으로만 LLM에 전달합니다.
     */
    private ArticleAnalysisResult analyzeArticleWithMetadata(String body, String knownTitle) {
        try {
            String analysisPrompt = buildMetadataAnalysisPrompt(body, knownTitle);
            String rawResponse = callGpt4oMini(analysisPrompt);

            // GPT가 ```json ... ``` 마크다운으로 감싸서 반환하는 경우를 처리
            String analysisResponse = rawResponse
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            log.info("기사 분석 결과: {}", analysisResponse);

            Map<String, Object> result = objectMapper.readValue(analysisResponse, new TypeReference<>() {
            });

            String title = (String) result.getOrDefault("title", "");
            String reporter = (String) result.getOrDefault("reporter", "");
            String summary = (String) result.getOrDefault("summary", "");
            @SuppressWarnings("unchecked")
            List<String> keywords = (List<String>) result.getOrDefault("keywords", new ArrayList<>());

            return new ArticleAnalysisResult(title, reporter, summary, keywords);

        } catch (Exception e) {
            log.error("기사 분석 중 오류 발생", e);
            return new ArticleAnalysisResult("", "", "", new ArrayList<>());
        }
    }

    /**
     * 텍스트의 임베딩 벡터를 생성합니다.
     * 
     * @param text 임베딩할 텍스트
     * @return 임베딩 벡터 (List<Double>)
     */
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

    /**
     * GPT-4o-mini 모델을 호출합니다.
     */
    private String callGpt4oMini(String prompt) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(
                        new ChatMessage(ChatMessageRole.SYSTEM.value(),
                                "당신은 신문 기사를 분석하는 전문가입니다. 항상 JSON 형식으로 응답해주세요."),
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

    /**
     * 기사 메타데이터 및 분석을 위한 프롬프트 생성
     * knownTitle이 있으면 그대로 title 필드에 넣어달라고 명시해 LLM 오인식을 방지합니다.
     */
    private String buildMetadataAnalysisPrompt(String body, String knownTitle) {
        String titleInstruction = knownTitle.isEmpty()
                ? "1. 기사의 제목 추출 (본문에서 가장 눈에 띄는 헤드라인)"
                : String.format("1. 기사 제목은 이미 확정되었습니다. title 필드에 반드시 \"%s\"를 그대로 사용하세요.", knownTitle);

        return String.format("""
                다음은 신문 기사 본문입니다. 아래 작업을 수행해주세요.

                기사 본문:
                %s

                작업:
                %s
                2. 기자 이름 추출 ("홍길동 기자", "홍길동 hong@example.com" 형식에서 이름만. 없으면 빈 문자열)
                3. 기사 내용을 2-3문장으로 요약
                4. 핵심 키워드 3개 추출 (단어 또는 짧은 구문)

                코드 블록 없이 순수 JSON만 응답하세요:
                {"title": "...", "reporter": "...", "summary": "...", "keywords": ["...", "...", "..."]}
                """, body, titleInstruction);
    }

    /**
     * 기사 분석 결과를 담는 내부 클래스
     */
    private static class ArticleAnalysisResult {
        private final String title;
        private final String reporter;
        private final String summary;
        private final List<String> keywords;

        public ArticleAnalysisResult(String title, String reporter, String summary, List<String> keywords) {
            this.title = title;
            this.reporter = reporter;
            this.summary = summary;
            this.keywords = keywords;
        }

        public String getTitle() {
            return title;
        }

        public String getReporter() {
            return reporter;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getKeywords() {
            return keywords;
        }
    }
}
