package com.qroad.be.service;

import com.qroad.be.domain.PaperEntity;
import com.qroad.be.domain.ArticleKeywordEntity;
import com.qroad.be.domain.ArticleEntity;
// import com.qroad.be.domain.QrCodeEntity; // QR 코드 기능 비활성화
import com.qroad.be.domain.AdminEntity;
import com.qroad.be.dto.*;
import com.qroad.be.repository.ArticleRepository;
import com.qroad.be.repository.PaperRepository;
// import com.qroad.be.repository.QrCodeRepository; // QR 코드 기능 비활성화
import com.qroad.be.repository.ArticleKeywordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaperService {

        private final PaperRepository paperRepository;
        private final ArticleRepository articleRepository;
        // private final QrCodeRepository qrCodeRepository; // QR 코드 기능 비활성화
        private final ArticleKeywordRepository articleKeywordRepository;
        private final com.qroad.be.repository.KeywordRepository keywordRepository;
        private final com.qroad.be.repository.ArticleRelatedRepository articleRelatedRepository;
        private final com.qroad.be.external.llm.LlmService llmService;
        private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
        private final com.qroad.be.repository.PolicyRepository policyRepository;
        private final com.qroad.be.repository.PolicyArticleRelatedRepository policyArticleRelatedRepository;

        /**
         * API 1: 발행된 신문 리스트 조회
         */
        public PublicationListResponse getPublications(int page, int limit, Long adminId) {
                Pageable pageable = PageRequest.of(page - 1, limit);

                Page<PaperEntity> paperPage = paperRepository
                                .findByAdminIdAndStatusOrderByPublishedDateDesc(adminId, "ACTIVE", pageable);

                List<PaperSummaryDto> papers = paperPage.getContent().stream()
                                .map(PaperSummaryDto::from)
                                .collect(Collectors.toList());

                return new PublicationListResponse(
                                paperPage.getTotalElements(),
                                papers);
        }

        /**
         * API 2: 발행 상세 조회
         */
        public PublicationDetailResponse getPublicationDetail(Long paperId) {
                PaperEntity paper = paperRepository.findById(paperId)
                                .orElseThrow(() -> new RuntimeException("신문을 찾을 수 없습니다."));

                List<ArticleEntity> articles = articleRepository.findByPaper_IdAndStatus(paperId, "ACTIVE");

                List<ArticleDto> articleDtos = articles.stream()
                                .map(article -> {
                                        List<String> keywords = getKeywordsForArticle(article.getId());
                                        return ArticleDto.builder()
                                                        .id(article.getId())
                                                        .title(article.getTitle())
                                                        .summary(article.getSummary())
                                                        .keywords(keywords)
                                                        .build();
                                })
                                .collect(Collectors.toList());

                return PublicationDetailResponse.from(paper, articleDtos);
        }

        private List<String> getKeywordsForArticle(Long articleId) {
                return articleKeywordRepository.findByArticle_Id(articleId).stream()
                                .map(ak -> ak.getKeyword().getName())
                                .collect(Collectors.toList());
        }

        /**
         * API 3: QR 발행 (비활성화 - DB에 qr_codes 테이블 없음)
         */
        /*
         * @Transactional
         * public QrCodeResponse generateQrCode(Long paperId) {
         * PaperEntity paper = paperRepository.findById(paperId)
         * .orElseThrow(() -> new RuntimeException("Paper not found"));
         * 
         * String qrKey = generateUniqueQrKey();
         * String targetUrl = "https://qroad.com/qr/" + qrKey; // 실제 도메인으로 변경 필요
         * String qrImageUrl = "https://qroad.com/qr/image/" + qrKey; // 예시 이미지 URL
         * 
         * QrCodeEntity qrCode = QrCodeEntity.builder()
         * .paper(paper)
         * .qrKey(qrKey)
         * .targetUrl(targetUrl)
         * .qrImageUrl(qrImageUrl)
         * .status("ACTIVE")
         * .build();
         * 
         * qrCodeRepository.save(qrCode);
         * 
         * return new QrCodeResponse(qrKey, qrImageUrl, targetUrl);
         * }
         * 
         * private String generateUniqueQrKey() {
         * String qrKey;
         * do {
         * qrKey = UUID.randomUUID().toString().substring(0, 8);
         * } while (qrCodeRepository.existsByQrKey(qrKey));
         * return qrKey;
         * }
         */

        /**
         * 신문 지면 생성 및 기사 청킹/분석
         * 1. Paper 저장
         * 2. GPT로 기사 청킹 및 요약/키워드 추출
         * 3. Article 저장
         * 4. Keyword 저장 (중복 체크)
         * 5. ArticleKeyword 매핑
         * 6. 임베딩 생성 및 vector_articles 저장
         * 7. 연관 기사 생성 (updateRelatedArticles 호출)
         * 8. 생성된 기사 정보 반환
         */
        @Transactional
        public com.qroad.be.dto.PaperCreateResponseDTO createPaperWithArticles(
                        com.qroad.be.dto.PaperCreateRequestDTO request, Long adminId) {
                log.info("신문 지면 생성 시작: title={}, publishedDate={}, adminId={}",
                                request.getTitle(), request.getPublishedDate(), adminId);

                // Admin 조회
                AdminEntity admin = null;
                if (adminId != null) {
                        admin = new AdminEntity();
                        admin.setId(adminId);
                }

                // 1. Paper 저장
                PaperEntity paper = PaperEntity.builder()
                                .title(request.getTitle())
                                .content(request.getContent())
                                .publishedDate(request.getPublishedDate())
                                .status("ACTIVE")
                                .admin(admin)
                                .build();

                PaperEntity savedPaper = paperRepository.save(paper);
                log.info("Paper 저장 완료: id={}, adminId={}", savedPaper.getId(), adminId);

                // 2. GPT로 기사 청킹 및 분석
                List<com.qroad.be.dto.ArticleChunkDTO> articleChunks = llmService
                                .chunkAndAnalyzePaper(request.getContent());

                log.info("총 {}개의 기사 청킹 완료", articleChunks.size());

                // 3. 각 기사 저장 및 키워드 매핑 + 응답 데이터 수집
                List<com.qroad.be.dto.PaperCreateResponseDTO.ArticleResponseDTO> articleResponses = new ArrayList<>();

                for (com.qroad.be.dto.ArticleChunkDTO chunk : articleChunks) {
                        // Article 저장
                        ArticleEntity article = ArticleEntity.builder()
                                        .title(chunk.getTitle())
                                        .content(chunk.getContent())
                                        .summary(chunk.getSummary())
                                        .reporter(chunk.getReporter())
                                        .link("") // 기본값
                                        .status("ACTIVE")
                                        .paper(savedPaper)
                                        .admin(admin)
                                        .build();

                        ArticleEntity savedArticle = articleRepository.save(article);
                        log.info("Article 저장 완료: id={}, title={}, adminId={}",
                                        savedArticle.getId(), savedArticle.getTitle(), adminId);

                        // 키워드 저장 및 매핑
                        List<String> savedKeywords = new ArrayList<>();
                        for (String keywordName : chunk.getKeywords()) {
                                if (keywordName == null || keywordName.trim().isEmpty()) {
                                        continue;
                                }

                                // 키워드 존재 여부 확인 후 저장
                                com.qroad.be.domain.KeywordEntity keyword = keywordRepository
                                                .findByName(keywordName.trim())
                                                .orElseGet(() -> {
                                                        com.qroad.be.domain.KeywordEntity newKeyword = com.qroad.be.domain.KeywordEntity
                                                                        .builder()
                                                                        .name(keywordName.trim())
                                                                        .build();
                                                        return keywordRepository.save(newKeyword);
                                                });

                                // ArticleKeyword 매핑
                                ArticleKeywordEntity articleKeyword = ArticleKeywordEntity.builder()
                                                .article(savedArticle)
                                                .keyword(keyword)
                                                .build();

                                articleKeywordRepository.save(articleKeyword);
                                savedKeywords.add(keywordName.trim());
                                log.info("ArticleKeyword 매핑 완료: articleId={}, keyword={}",
                                                savedArticle.getId(), keywordName);
                        }

                        // 임베딩 생성 및 저장
                        // 주석: 발행 API로 생성된 기사는 vector_articles에 저장하지 않음
                        // 이유: link 컬럼이 없어서 다른 기사의 연관 기사로 매핑될 때 오류 발생
                        // 발행 기사는 크롤링 기사를 연관 기사로 찾을 수 있지만, 역방향 매핑은 되지 않음
                        /*
                         * try {
                         * List<Double> embedding = llmService.getEmbedding(chunk.getContent());
                         * String vectorString = embedding.toString(); // [0.1, 0.2, ...] 형식
                         * 
                         * String sql =
                         * "INSERT INTO vector_articles (article_id, title, published_date, vector) VALUES (?, ?, ?, ?::vector)"
                         * ;
                         * jdbcTemplate.update(sql, savedArticle.getId(), savedArticle.getTitle(),
                         * savedPaper.getPublishedDate(), vectorString);
                         * log.info("VectorArticle 저장 완료: articleId={}", savedArticle.getId());
                         * } catch (Exception e) {
                         * log.error("임베딩 저장 실패: articleId={}", savedArticle.getId(), e);
                         * }
                         */

                        // 연관 기사 생성
                        updateRelatedArticles(savedArticle.getId(), savedKeywords);
                        // 연관 정책 생성
                        updateRelatedPolicies(savedArticle.getId(), savedKeywords);

                        // 응답 DTO 생성
                        com.qroad.be.dto.PaperCreateResponseDTO.ArticleResponseDTO articleResponse = com.qroad.be.dto.PaperCreateResponseDTO.ArticleResponseDTO
                                        .builder()
                                        .id(savedArticle.getId())
                                        .title(savedArticle.getTitle())
                                        .summary(savedArticle.getSummary())
                                        .keywords(savedKeywords)
                                        .build();

                        articleResponses.add(articleResponse);
                }

                log.info("신문 지면 생성 완료: paperId={}, 기사 수={}, adminId={}",
                                savedPaper.getId(), articleChunks.size(), adminId);

                // 최종 응답 생성
                return com.qroad.be.dto.PaperCreateResponseDTO.builder()
                                .paperId(savedPaper.getId())
                                .articleCount(articleResponses.size())
                                .articles(articleResponses)
                                .build();
        }

        /**
         * 연관 기사 업데이트
         * 1. 기존 연관 기사 삭제
         * 2. 키워드 기반 임베딩 생성
         * 3. 벡터 유사도 검색으로 연관 기사 3개 추출
         * 4. 연관 기사 저장
         */
        @Transactional
        public void updateRelatedArticles(Long articleId, List<String> keywords) {
                if (keywords == null || keywords.isEmpty()) {
                        return;
                }

                // 1. 기존 연관 기사 삭제
                articleRelatedRepository.deleteByArticleId(articleId);

                // 2. 키워드 기반 임베딩 생성
                String keywordText = String.join(" ", keywords);
                List<Double> embedding;
                try {
                        embedding = llmService.getEmbedding(keywordText);
                } catch (Exception e) {
                        log.error("연관 기사 생성을 위한 임베딩 실패: articleId={}", articleId, e);
                        return;
                }
                String vectorString = embedding.toString();

                // 3. 벡터 유사도 검색 (L2 거리 기준, 자기 자신 제외, 상위 3개)
                // vector_articles 테이블에서 검색
                String sql = """
                                    SELECT article_id, vector <-> ?::vector as distance
                                    FROM vector_articles
                                    WHERE article_id != ?
                                    ORDER BY distance ASC
                                    LIMIT 3
                                """;

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, vectorString, articleId);

                ArticleEntity sourceArticle = articleRepository.findById(articleId)
                                .orElseThrow(() -> new RuntimeException("Article not found: " + articleId));

                // 4. 연관 기사 저장
                for (Map<String, Object> row : rows) {
                        Long relatedArticleId = ((Number) row.get("article_id")).longValue();
                        Double distance = ((Number) row.get("distance")).doubleValue();
                        // 유사도 점수 변환 (거리가 0이면 유사도 1, 거리가 멀수록 0에 수렴하도록)
                        // 간단하게 1 / (1 + distance) 사용하거나, 그냥 distance 저장 (여기서는 distance가 작을수록 유사함)
                        // ArticleRelatedEntity의 score는 높을수록 유사한 것으로 가정하면 변환 필요.
                        // 여기서는 1 - distance (코사인 거리의 경우) 등을 쓸 수 있으나, L2 거리이므로 적절히 변환.
                        // 일단 distance 자체를 저장하거나, 1/(1+distance)로 저장.
                        Double score = 1.0 / (1.0 + distance);

                        ArticleEntity relatedArticle = articleRepository.findById(relatedArticleId)
                                        .orElse(null);

                        if (relatedArticle != null) {
                                com.qroad.be.domain.ArticleRelatedEntity relatedEntity = com.qroad.be.domain.ArticleRelatedEntity
                                                .builder()
                                                .article(sourceArticle)
                                                .relatedArticle(relatedArticle)
                                                .score(score)
                                                .batchDate(java.time.LocalDate.now())
                                                .build();

                                articleRelatedRepository.save(relatedEntity);
                                log.info("연관 기사 저장 완료: source={}, related={}, score={}", articleId, relatedArticleId,
                                                score);
                        }
                }
        }

        /**
         * 연관 정책 업데이트 (키워드 기반 벡터 유사도 검색)
         */
        @Transactional
        public void updateRelatedPolicies(Long articleId, List<String> keywords) {
                if (keywords == null || keywords.isEmpty()) {
                        return;
                }

                // 1. 기존 연관 정책 삭제
                policyArticleRelatedRepository.deleteByArticleId(articleId);

                // 2. 키워드 기반 임베딩 생성
                String keywordText = String.join(" ", keywords);
                List<Double> embedding;
                try {
                        embedding = llmService.getEmbedding(keywordText);
                } catch (Exception e) {
                        log.error("연관 정책 생성을 위한 임베딩 실패: articleId={}", articleId, e);
                        return;
                }
                String vectorString = embedding.toString();

                // 3. 벡터 유사도 검색 (L2 거리 기준, 상위 3개)
                String sql = """
                                    SELECT policy_id, vector <-> ?::vector as distance
                                    FROM vector_policy
                                    ORDER BY distance ASC
                                    LIMIT 3
                                """;

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, vectorString);

                ArticleEntity sourceArticle = articleRepository.findById(articleId)
                                .orElseThrow(() -> new RuntimeException("Article not found: " + articleId));

                // 4. 연관 정책 저장
                for (Map<String, Object> row : rows) {
                        Long policyId = ((Number) row.get("policy_id")).longValue();
                        Double distance = ((Number) row.get("distance")).doubleValue();
                        Double score = 1.0 / (1.0 + distance);

                        com.qroad.be.domain.PolicyEntity policy = policyRepository.findById(policyId)
                                        .orElse(null);

                        if (policy != null) {
                                com.qroad.be.domain.PolicyArticleRelatedEntity relatedEntity = com.qroad.be.domain.PolicyArticleRelatedEntity
                                                .builder()
                                                .article(sourceArticle)
                                                .policy(policy)
                                                .score(score)
                                                .batchDate(java.time.LocalDate.now())
                                                .status("active")
                                                .build();

                                policyArticleRelatedRepository.save(relatedEntity);
                                log.info("연관 정책 저장 완료: article={}, policy={}, score={}",
                                                articleId, policyId, score);
                        }
                }
        }

        /**
         * 기사 수정 (요약 및 키워드)
         */
        @Transactional
        public com.qroad.be.dto.ArticleUpdateResponseDTO updateArticle(Long articleId,
                        com.qroad.be.dto.ArticleUpdateRequestDTO request) {
                ArticleEntity article = articleRepository.findById(articleId)
                                .orElseThrow(() -> new RuntimeException("Article not found"));

                // 요약 수정
                if (request.getSummary() != null) {
                        article.setSummary(request.getSummary());
                }

                // 키워드 수정
                if (request.getKeywords() != null) {
                        // 기존 키워드 매핑 삭제
                        articleKeywordRepository.deleteByArticleId(articleId);

                        // 새 키워드 저장 및 매핑
                        for (String keywordName : request.getKeywords()) {
                                if (keywordName == null || keywordName.trim().isEmpty())
                                        continue;

                                com.qroad.be.domain.KeywordEntity keyword = keywordRepository
                                                .findByName(keywordName.trim())
                                                .orElseGet(() -> keywordRepository
                                                                .save(com.qroad.be.domain.KeywordEntity.builder()
                                                                                .name(keywordName.trim())
                                                                                .build()));

                                articleKeywordRepository.save(ArticleKeywordEntity.builder()
                                                .article(article)
                                                .keyword(keyword)
                                                .build());
                        }

                        // 연관 기사 업데이트
                        updateRelatedArticles(articleId, request.getKeywords());
                        // 연관 정책 업데이트
                        updateRelatedPolicies(articleId, request.getKeywords());
                }

                ArticleEntity savedArticle = articleRepository.save(article);

                // 최신 키워드 조회
                List<String> currentKeywords = getKeywordsForArticle(savedArticle.getId());

                return com.qroad.be.dto.ArticleUpdateResponseDTO.builder()
                                .articleId(savedArticle.getId())
                                .title(savedArticle.getTitle())
                                .summary(savedArticle.getSummary())
                                .keywords(currentKeywords)
                                .build();
        }
}