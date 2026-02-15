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

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

            // 1단계: 규칙 기반으로 신문 지면을 기사 단위로 청킹
            List<String> rawArticleContents = chunkByReporterPattern(paperContent);

            log.info("총 {}개의 기사 청킹 완료", rawArticleContents.size());

            // 2단계: 각 기사별로 LLM을 사용하여 제목, 기자명, 요약문, 키워드 추출
            List<ArticleChunkDTO> articles = new ArrayList<>();
            int total = rawArticleContents.size();
            for (int i = 0; i < total; i++) {
                String articleContent = rawArticleContents.get(i);
                // LLM으로 메타데이터 및 분석 정보 추출
                ArticleAnalysisResult analysis = analyzeArticleWithMetadata(articleContent);

                ArticleChunkDTO article = ArticleChunkDTO.builder()
                        .title(analysis.getTitle())
                        .content(articleContent)
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
     * 규칙 기반으로 신문 지면을 기사 단위로 청킹합니다.
     * 기자명 패턴('이름 기자' 또는 '이름 이메일')을 기준으로 분리합니다.
     *
     * @param paperContent 신문 지면 원문
     * @return 청킹된 기사 원문 리스트
     */
    private List<String> chunkByReporterPattern(String paperContent) {
        List<String> articles = new ArrayList<>();

        // 패턴 1: 한글 이름(2-4자) + 공백(선택) + "기자"
        // 패턴 2: 한글 이름(2-4자) + 공백 + 이메일
        String pattern = "([가-힣]{2,4})\\s*기자|([가-힣]{2,4})\\s+([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})";
        java.util.regex.Pattern reporterPattern = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher matcher = reporterPattern.matcher(paperContent);

        List<Integer> splitPositions = new ArrayList<>();
        splitPositions.add(0); // 시작 위치

        while (matcher.find()) {
            // 기자명 패턴이 발견된 위치의 끝을 기사 경계로 사용
            splitPositions.add(matcher.end());
        }

        // 기사 분리
        for (int i = 0; i < splitPositions.size(); i++) {
            int start = splitPositions.get(i);
            int end = (i + 1 < splitPositions.size()) ? splitPositions.get(i + 1) : paperContent.length();

            String articleContent = paperContent.substring(start, end).trim();

            // 마지막 청크인 경우, 기자명 패턴으로 끝나는지 확인
            if (i == splitPositions.size() - 1 && i > 0) {
                // 마지막 청크가 기자명 패턴으로 끝나지 않으면 버림
                java.util.regex.Matcher endMatcher = reporterPattern.matcher(articleContent);
                boolean endsWithReporter = false;
                int lastMatchEnd = -1;

                while (endMatcher.find()) {
                    lastMatchEnd = endMatcher.end();
                }

                // 마지막 매칭 위치가 청크 끝 부분(여백 고려하여 10자 이내)이면 유효한 기사로 간주
                if (lastMatchEnd > 0 && articleContent.length() - lastMatchEnd <= 10) {
                    endsWithReporter = true;
                }

                if (!endsWithReporter) {
                    log.info("마지막 청크가 기자명으로 끝나지 않아 제외합니다: {}",
                            articleContent.length() > 50 ? articleContent.substring(0, 50) + "..." : articleContent);
                    continue;
                }
            }

            if (!articleContent.isEmpty()) {
                articles.add(articleContent);
            }
        }

        // 패턴이 하나도 발견되지 않은 경우 전체를 하나의 기사로 간주
        if (articles.isEmpty()) {
            log.warn("기자명 패턴이 발견되지 않아 전체를 하나의 기사로 처리합니다.");
            articles.add(paperContent.trim());
        }

        return articles;
    }

    /**
     * 개별 기사의 제목, 기자명, 요약문, 키워드를 추출합니다.
     */
    private ArticleAnalysisResult analyzeArticleWithMetadata(String content) {
        try {
            String analysisPrompt = buildMetadataAnalysisPrompt(content);
            String analysisResponse = callGpt4oMini(analysisPrompt);

            log.info("기사 분석 결과: {}", analysisResponse);

            // JSON 파싱
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
            // 오류 발생 시 기본값 반환
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
     */
    private String buildMetadataAnalysisPrompt(String content) {
        return String.format("""
                다음은 신문 기사의 원문입니다. 이 기사를 분석해주세요.

                기사 원문:
                %s

                다음 작업을 수행해주세요:
                1. 기사의 제목 추출 (보통 첫 줄이나 가장 눈에 띄는 헤드라인)
                2. 기자 이름 추출 ("홍길동 기자", "홍길동 hong@example.com" 등의 형식에서 이름만)
                3. 기사 내용을 2-3문장으로 요약
                4. 핵심 키워드 3개 추출 (단어 또는 짧은 구문)

                반드시 다음과 같은 JSON 형식으로 응답해주세요:
                {
                  "title": "기사 제목",
                  "reporter": "기자 이름",
                  "summary": "요약문...",
                  "keywords": ["키워드1", "키워드2", "키워드3"]
                }
                """, content);
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
