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

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 신문 지면 전체 내용을 기사 단위로 청킹하고, 각 기사의 요약문과 키워드를 추출합니다.
     *
     * @param paperContent 신문 지면 원문 전체
     * @return 청킹된 기사 리스트 (제목, 내용, 기자명, 요약문, 키워드)
     */
    public List<ArticleChunkDTO> chunkAndAnalyzePaper(String paperContent) {
        try {
            log.info("신문 지면 청킹 및 분석 시작");

            // 1단계: 신문 지면을 기사 단위로 청킹
            String chunkingPrompt = buildChunkingPrompt(paperContent);
            String chunkingResponse = callGpt4oMini(chunkingPrompt);

            log.info("청킹 결과: {}", chunkingResponse);

            // JSON 파싱
            List<Map<String, String>> rawArticles = parseJsonResponse(chunkingResponse);

            // 2단계: 각 기사별로 요약문과 키워드 추출
            List<ArticleChunkDTO> articles = new ArrayList<>();
            for (Map<String, String> rawArticle : rawArticles) {
                String title = rawArticle.getOrDefault("title", "");
                String content = rawArticle.getOrDefault("content", "");
                String reporter = rawArticle.getOrDefault("reporter", "");

                // 요약문 및 키워드 추출
                ArticleAnalysisResult analysis = analyzeArticle(title, content);

                ArticleChunkDTO article = ArticleChunkDTO.builder()
                        .title(title)
                        .content(content)
                        .reporter(reporter)
                        .summary(analysis.getSummary())
                        .keywords(analysis.getKeywords())
                        .build();

                articles.add(article);
            }

            log.info("총 {}개의 기사 청킹 및 분석 완료", articles.size());
            return articles;

        } catch (Exception e) {
            log.error("신문 지면 청킹 및 분석 중 오류 발생", e);
            throw new RuntimeException("신문 지면 처리 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * 개별 기사의 요약문과 키워드를 추출합니다.
     */
    private ArticleAnalysisResult analyzeArticle(String title, String content) {
        try {
            String analysisPrompt = buildAnalysisPrompt(title, content);
            String analysisResponse = callGpt4oMini(analysisPrompt);

            log.info("기사 분석 결과: {}", analysisResponse);

            // JSON 파싱
            Map<String, Object> result = objectMapper.readValue(analysisResponse, new TypeReference<>() {
            });

            String summary = (String) result.getOrDefault("summary", "");
            @SuppressWarnings("unchecked")
            List<String> keywords = (List<String>) result.getOrDefault("keywords", new ArrayList<>());

            return new ArticleAnalysisResult(summary, keywords);

        } catch (Exception e) {
            log.error("기사 분석 중 오류 발생: title={}", title, e);
            // 오류 발생 시 기본값 반환
            return new ArticleAnalysisResult("", new ArrayList<>());
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
     * 신문 지면 청킹을 위한 프롬프트 생성
     */
    private String buildChunkingPrompt(String paperContent) {
        return String.format("""
                다음은 신문 지면의 원문입니다. 이 내용을 개별 기사 단위로 분리해주세요.

                각 기사는 다음 정보를 포함해야 합니다:
                1. title: 기사 제목
                2. content: 기사 본문 전체
                3. reporter: 기자 이름 (없으면 빈 문자열)

                반드시 다음과 같은 JSON 배열 형식으로 응답해주세요:
                [
                  {
                    "title": "기사 제목",
                    "content": "기사 본문...",
                    "reporter": "기자명"
                  },
                  ...
                ]

                신문 지면 원문:
                %s
                """, paperContent);
    }

    /**
     * 기사 분석(요약 및 키워드 추출)을 위한 프롬프트 생성
     */
    private String buildAnalysisPrompt(String title, String content) {
        return String.format("""
                다음 기사를 분석해주세요.

                제목: %s
                본문: %s

                다음 작업을 수행해주세요:
                1. 기사 내용을 2-3문장으로 요약
                2. 핵심 키워드 3개 추출 (단어 또는 짧은 구문)

                반드시 다음과 같은 JSON 형식으로 응답해주세요:
                {
                  "summary": "요약문...",
                  "keywords": ["키워드1", "키워드2", "키워드3"]
                }
                """, title, content);
    }

    /**
     * JSON 응답을 파싱합니다.
     */
    private List<Map<String, String>> parseJsonResponse(String jsonResponse) {
        try {
            // JSON 코드 블록 제거 (```json ... ``` 형식인 경우)
            String cleanJson = jsonResponse.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            return objectMapper.readValue(cleanJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("JSON 파싱 오류: {}", jsonResponse, e);
            throw new RuntimeException("응답 파싱 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * 기사 분석 결과를 담는 내부 클래스
     */
    private static class ArticleAnalysisResult {
        private final String summary;
        private final List<String> keywords;

        public ArticleAnalysisResult(String summary, List<String> keywords) {
            this.summary = summary;
            this.keywords = keywords;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> keywords() {
            return keywords;
        }

        public List<String> getKeywords() {
            return keywords;
        }
    }
}