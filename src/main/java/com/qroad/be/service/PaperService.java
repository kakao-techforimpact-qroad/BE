package com.qroad.be.service;

import com.qroad.be.domain.PaperEntity;
import com.qroad.be.domain.ArticleKeywordEntity;
import com.qroad.be.domain.ArticleEntity;
// import com.qroad.be.domain.QrCodeEntity; // QR 코드 기능 비활성화
import com.qroad.be.domain.AdminEntity;
import com.qroad.be.dto.*;
import com.qroad.be.pdf.PdfExtractorService;
import com.qroad.be.progress.PublicationProgressStore;
import com.qroad.be.progress.PublicationStep;
import com.qroad.be.repository.ArticleRepository;
import com.qroad.be.repository.PaperRepository;
// import com.qroad.be.repository.QrCodeRepository; // QR 코드 기능 비활성화
import com.qroad.be.repository.ArticleKeywordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.qroad.be.pdf.PdfExtractorService.ExtractionResult;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaperService {

        private static final List<String> NON_ARTICLE_TITLE_KEYWORDS = List.of(
                        "\ubaa8\uc9d1", "\ucc44\uc6a9", "\uad6c\uc778", "\uacf5\uace0",
                        "\uc54c\ub9bd\ub2c8\ub2e4", "\uc8fc\ubbfc\uac8c\uc2dc\ud310", "\uc6b0\ub9ac\ub3d9\ub124",
                        "\uc0c1\uac00", "\uc0ac\ubb34\uc2e4", "\uc138 \ub193\uc2b5\ub2c8\ub2e4", "\ud301\ub2c8\ub2e4",
                        "\uc0ac\ub78c\uad6c\ud569\ub2c8\ub2e4", "\uc8fc\ud0dd", "\ud1a0\uc9c0", "\ucc3d\uace0");
        private static final List<Pattern> NON_ARTICLE_TITLE_PATTERNS = List.of(
                        Pattern.compile("(?i)\\uc0ac\\s*\\uace0\\s*\\(\\s*\\u793e\\s*\\u544a\\s*\\)"),
                        Pattern.compile("(?i)^\\s*\\uc54c\\ub9bd\\ub2c8\ub2e4\\s*$"),
                        Pattern.compile("(?i)^\\s*\\uc8fc\\ubbfc\\s*\\uac8c\\uc2dc\\ud310\\s*$"));

        private static final List<String> AD_CATEGORY_KEYWORDS = List.of(
                "광고", "홍보", "공고", "채용", "모집", "입찰"
        );

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
        private final PublicationProgressStore publicationProgressStore;
        private final S3PresignService s3PresignService;
        private final PdfExtractorService pdfExtractorService;
        private final PythonPdfSegmentationService pythonPdfSegmentationService;

        /**
         * API 1: 발행된 신문 리스트 조회
         */
        public PublicationListResponse getPublications(int page, int limit, Long adminId, YearMonth month, String q) {
                Pageable pageable = PageRequest.of(page - 1, limit, Sort.by(Sort.Direction.DESC, "publishedDate"));
                LocalDate monthStart = null;
                LocalDate monthEnd = null;
                if (month != null) {
                        monthStart = month.atDay(1);
                        monthEnd = month.plusMonths(1).atDay(1);
                }

                String normalizedQ = StringUtils.hasText(q) ? q.trim() : null;
                String qLikePattern = normalizedQ != null ? "%" + normalizedQ.toLowerCase() + "%" : null;
                String qIssueLikePattern = normalizedQ != null ? "%" + normalizedQ + "%" : null;
                LocalDate qMonthStart = null;
                LocalDate qMonthEnd = null;
                if (normalizedQ != null && normalizedQ.matches("^\\d{4}-\\d{2}$")) {
                        YearMonth qMonth = YearMonth.parse(normalizedQ);
                        qMonthStart = qMonth.atDay(1);
                        qMonthEnd = qMonth.plusMonths(1).atDay(1);
                }

                final LocalDate finalMonthStart = monthStart;
                final LocalDate finalMonthEnd = monthEnd;
                final LocalDate finalQMonthStart = qMonthStart;
                final LocalDate finalQMonthEnd = qMonthEnd;
                final String finalQLikePattern = qLikePattern;
                final String finalQIssueLikePattern = qIssueLikePattern;
                final String activeStatus = "ACTIVE";

                Specification<PaperEntity> spec = (root, query, cb) -> {
                        List<Predicate> predicates = new ArrayList<>();
                        predicates.add(cb.equal(root.get("admin").get("id"), adminId));
                        predicates.add(cb.equal(root.get("status"), activeStatus));

                        if (finalMonthStart != null) {
                                predicates.add(cb.greaterThanOrEqualTo(root.get("publishedDate"), finalMonthStart));
                                predicates.add(cb.lessThan(root.get("publishedDate"), finalMonthEnd));
                        }

                        if (finalQLikePattern != null) {
                                List<Predicate> qPredicates = new ArrayList<>();
                                // "호수 문자열(YYYY-MM)" 부분 검색: q=2026 -> 2026-02 매칭
                                qPredicates.add(cb.like(
                                                cb.function("to_char", String.class, root.get("publishedDate"),
                                                                cb.literal("YYYY-MM")),
                                                finalQIssueLikePattern));
                                qPredicates.add(cb.like(cb.lower(root.get("title")), finalQLikePattern));

                                var articleTitleExists = query.subquery(Long.class);
                                var article = articleTitleExists.from(ArticleEntity.class);
                                articleTitleExists.select(cb.literal(1L));
                                articleTitleExists.where(
                                                cb.equal(article.get("paper"), root),
                                                cb.equal(article.get("status"), activeStatus),
                                                cb.like(cb.lower(article.get("title")), finalQLikePattern));
                                qPredicates.add(cb.exists(articleTitleExists));

                                if (finalQMonthStart != null) {
                                        qPredicates.add(cb.and(
                                                        cb.greaterThanOrEqualTo(root.get("publishedDate"),
                                                                        finalQMonthStart),
                                                        cb.lessThan(root.get("publishedDate"), finalQMonthEnd)));
                                }

                                predicates.add(cb.or(qPredicates.toArray(new Predicate[0])));
                        }

                        query.distinct(true);
                        return cb.and(predicates.toArray(new Predicate[0]));
                };

                Page<PaperEntity> paperPage = paperRepository.findAll(spec, pageable);

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

                List<ArticleEntity> articles = articleRepository.findByPaper_IdAndStatusOrderByIdAsc(paperId, "ACTIVE");
                Map<Long, List<String>> keywordsByArticleId = getKeywordsForArticles(articles);

                List<ArticleDto> articleDtos = articles.stream()
                                .map(article -> {
                                        List<String> keywords = keywordsByArticleId.getOrDefault(
                                                        article.getId(),
                                                        Collections.emptyList());
                                        return ArticleDto.builder()
                                                        .id(article.getId())
                                                        .title(article.getTitle())
                                                        .summary(article.getSummary())
                                                        .keywords(keywords)
                                                        .imagePath(article.getImagePath())
                                                        .build();
                                })
                                .collect(Collectors.toList());

                return PublicationDetailResponse.from(paper, articleDtos);
        }

        private Map<Long, List<String>> getKeywordsForArticles(List<ArticleEntity> articles) {
                if (articles == null || articles.isEmpty()) {
                        return Collections.emptyMap();
                }

                List<Long> articleIds = articles.stream()
                                .map(ArticleEntity::getId)
                                .toList();

                List<Object[]> rows = articleKeywordRepository.findArticleIdAndKeywordNameByArticleIds(articleIds);

                Map<Long, List<String>> keywordsByArticleId = new HashMap<>();
                for (Object[] row : rows) {
                        Long articleId = ((Number) row[0]).longValue();
                        String keywordName = (String) row[1];
                        keywordsByArticleId
                                        .computeIfAbsent(articleId, ignored -> new ArrayList<>())
                                        .add(keywordName);
                }
                return keywordsByArticleId;
        }

        private List<String> getKeywordsForArticle(Long articleId) {
                return articleRepository.findKeywordNamesByArticleId(articleId);
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
                return createPaperWithArticles(request, adminId, null);
        }

        /**
         * jobId가 전달되면 단계별 진행률을 함께 기록한다.
         * 기존 동기 호출 호환을 위해 jobId는 nullable로 유지한다.
         */
        @Transactional
        public com.qroad.be.dto.PaperCreateResponseDTO createPaperWithArticles(
                        com.qroad.be.dto.PaperCreateRequestDTO request, Long adminId, String jobId) {
                log.info("신문 지면 생성 시작: title={}, publishedDate={}, adminId={}",
                                request.getTitle(), request.getPublishedDate(), adminId);

                // 파이썬 기반 분리 엔진 우선 사용
                ExtractionResult extraction = runInStep(jobId, PublicationStep.PDF_READING, () -> {
                        try {
                                byte[] pdfBytes = s3PresignService.readPdfBytes(request.getTempKey());
                                var output = pythonPdfSegmentationService.segment(
                                                pdfBytes,
                                                request.getTempKey(),
                                                (processed, total) -> {
                                                        if (jobId != null) {
                                                                publicationProgressStore.updatePdfReadingProgress(jobId, processed,
                                                                                total);
                                                        }
                                                });
                                log.info("파이썬 PDF 추출 완료: tempKey={}, textLength={}, articleCount={}",
                                                request.getTempKey(), output.getPaperContent().length(),
                                                output.getArticles().size());
                                return new ExtractionResult(output.getPaperContent(), Collections.emptyList());
                        } catch (Exception e) {
                                log.error("파이썬 PDF 추출 실패, 자바 엔진으로 폴백 시도: {}", e.getMessage());
                                try {
                                        byte[] pdfBytes = s3PresignService.readPdfBytes(request.getTempKey());
                                        return pdfExtractorService.extractWithImages(pdfBytes, (processed, total) -> {
                                                if (jobId != null) {
                                                        publicationProgressStore.updatePdfReadingProgress(jobId, processed, total);
                                                }
                                        });
                                } catch (Exception ex) {
                                        throw new RuntimeException("PDF 추출 최종 실패: " + request.getTempKey(), ex);
                                }
                        }
                });
                String content = extraction.getText();

                AdminEntity admin = null;
                if (adminId != null) {
                        admin = new AdminEntity();
                        admin.setId(adminId);
                }

                PaperEntity paper = PaperEntity.builder()
                                .title(request.getTitle())
                                .content(content)
                                .filePath(request.getTempKey()) // 임시값; finalize 후 덮어씀
                                .publishedDate(request.getPublishedDate())
                                .status("ACTIVE")
                                .admin(admin)
                                .build();

                PaperEntity savedPaper = paperRepository.save(paper);
                log.info("Paper 저장 완료: id={}, adminId={}", savedPaper.getId(), adminId);

                // 기사별 이미지를 S3에 업로드하고 제목->S3 key 맵을 생성 (현재는 사용하지 않음)
                // AI 이미지를 카테고리별로 매핑하게 되면서 더 이상 PDF 이미지를 추출/업로드하지 않습니다.
                // 기존 PDF 내 이미지 추출을 S3에 업로드하는 로직 제거됨

                List<com.qroad.be.dto.ArticleChunkDTO> articleChunks = runInStep(
                                jobId,
                                PublicationStep.CHUNKING_AND_ANALYZING,
                                () -> {
                                        if (jobId != null) {
                                                return llmService.chunkAndAnalyzePaper(
                                                                content,
                                                                (processed, total) -> publicationProgressStore
                                                                                .updateChunkingProgress(jobId,
                                                                                                processed, total));
                                        }
                                        return llmService.chunkAndAnalyzePaper(content);
                                });

                runInStep(jobId, PublicationStep.ANALYSIS_FINALIZING, () -> {
                });
                log.info("총 {}개의 기사 청킹 완료", articleChunks.size());

                final AdminEntity finalAdmin = admin;
                List<com.qroad.be.dto.PaperCreateResponseDTO.ArticleResponseDTO> articleResponses = new ArrayList<>();
                final int totalChunks = articleChunks.size();
                AtomicInteger relatedProcessed = new AtomicInteger(0);

                runInStep(jobId, PublicationStep.KEYWORD_MAPPING, () -> {
                        for (int i = 0; i < articleChunks.size(); i++) {
                                com.qroad.be.dto.ArticleChunkDTO chunk = articleChunks.get(i);
                                int index = i + 1;
                                if (isNonArticleTitle(chunk.getTitle(), chunk.getSummary())) {
                                        log.info("기사 저장 제외(비기사): index={}/{}, title={}",
                                                        index, totalChunks, chunk.getTitle());
                                        continue;
                                }
                                // 광고·홍보·공고 카테고리 기사 저장 제외
                                boolean isAdCategory = AD_CATEGORY_KEYWORDS.stream()
                                                .anyMatch(kw -> chunk.getCategory().contains(kw));
                                if (isAdCategory) {
                                        log.info("기사 저장 제외(광고/홍보/공고): index={}/{}, title={}, category={}",
                                                        index, totalChunks, chunk.getTitle(), chunk.getCategory());
                                        continue;
                                }

                                // 카테고리 기사 AI 이미지를 매핑
                                String imagePath = getCategoryImage(chunk.getCategory());

                                ArticleEntity article = ArticleEntity.builder()
                                                .title(chunk.getTitle())
                                                .content(chunk.getContent())
                                                .summary(chunk.getSummary())
                                                .reporter(chunk.getReporter())
                                                .link("") // 기본값
                                                .status("ACTIVE")
                                                .paper(savedPaper)
                                                .admin(finalAdmin)
                                                .imagePath(imagePath)
                                                .build();

                                ArticleEntity savedArticle = articleRepository.save(article);
                                log.info("기사 저장 완료: index={}/{}, articleId={}, title={}, category={}, adminId={}",
                                                index,
                                                totalChunks,
                                                savedArticle.getId(),
                                                savedArticle.getTitle(),
                                                chunk.getCategory(),
                                                adminId);

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

                                // 연관 항목 생성은 FINDING_RELATED 단계에서 별도 진행

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
                });

                runInStep(jobId, PublicationStep.FINDING_RELATED, () -> {
                        for (com.qroad.be.dto.PaperCreateResponseDTO.ArticleResponseDTO articleResponse : articleResponses) {
                                // 저장된 키워드 기반 연관 기사/정책 생성
                                updateRelatedArticles(articleResponse.getId(), articleResponse.getKeywords());
                                updateRelatedPolicies(articleResponse.getId(), articleResponse.getKeywords());

                                int processed = relatedProcessed.incrementAndGet();
                                if (jobId != null) {
                                        publicationProgressStore.updateRelatedProgress(jobId, processed, totalChunks);
                                }
                        }
                });

                runInStep(jobId, PublicationStep.SAVING, () -> {
                });
                log.info("신문 지면 생성 완료: paperId={}, 분석기사수={}, 저장기사수={}, adminId={}",
                                savedPaper.getId(), articleChunks.size(), articleResponses.size(), adminId);

                return com.qroad.be.dto.PaperCreateResponseDTO.builder()
                                .paperId(savedPaper.getId())
                                .articleCount(articleResponses.size())
                                .articles(articleResponses)
                                .build();
        }

        /**
         * 비동기 작업에서만 진행률을 갱신한다.
         */
        private void moveTo(String jobId, PublicationStep step) {
                if (jobId == null) {
                        return;
                }
                publicationProgressStore.moveTo(jobId, step);
        }

        private <T> T runInStep(String jobId, PublicationStep step, Supplier<T> action) {
                moveTo(jobId, step);
                return action.get();
        }

        private void runInStep(String jobId, PublicationStep step, Runnable action) {
                moveTo(jobId, step);
                action.run();
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

        /**
         * LLM이 분류한 카테고리에 맞는 미리 만들어진 AI 이미지 경로(S3 Key)를 반환합니다.
         */
        private boolean isNonArticleTitle(String title, String summary) {
                String rawTitle = title == null ? "" : title;
                String rawSummary = summary == null ? "" : summary;
                String mergedRaw = (rawTitle + " " + rawSummary).trim();
                String normalized = normalizeForMatch(mergedRaw);

                boolean byKeyword = NON_ARTICLE_TITLE_KEYWORDS.stream()
                                .map(this::normalizeForMatch)
                                .anyMatch(normalized::contains);
                if (byKeyword) {
                        return true;
                }
                return NON_ARTICLE_TITLE_PATTERNS.stream().anyMatch(p -> p.matcher(mergedRaw).find());
        }

        private String normalizeForMatch(String text) {
                if (text == null) {
                        return "";
                }
                return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }

        private String getCategoryImage(String category) {
                if (category == null)
                        return "ai-images/placeholder.png";

                                return switch (category) {
                        case "지방 행정" -> "ai-images/local-government.png";
                        case "지역 정치" -> "ai-images/local-politics.png";
                        case "공공 기관" -> "ai-images/public-institution.png";
                        case "지역 산업" -> "ai-images/regional-industry.png";
                        case "소상공인 및 시장" -> "ai-images/small-business-market.png";
                        case "부동산 및 개발" -> "ai-images/real-estate-development.png";
                        case "사건-사고" -> "ai-images/incidents-accidents.png";
                        case "시민 사회" -> "ai-images/civil-society.png";
                        case "교육 및 보건" -> "ai-images/education-health.png";
                        case "문화-예술" -> "ai-images/culture-arts.png";
                        case "여행-명소" -> "ai-images/travel-attractions.png";
                        case "생활 스포츠" -> "ai-images/sports-recreation.png";
                        case "지역 인물" -> "ai-images/local-figures.png";
                        case "독자 목소리" -> "ai-images/placeholder.png";
                        default -> "ai-images/placeholder.png";
                };
        }

        /**
         * finalize 완료 후 DB의 file_path 갱신
         */
        @Transactional
        public void updateFilePath(Long paperId, String filePath) {
                PaperEntity paper = paperRepository.findById(paperId)
                                .orElseThrow(() -> new RuntimeException("Paper not found: " + paperId));
                paper.setFilePath(filePath);
                log.info("filePath 갱신 완료: paperId={}, filePath={}", paperId, filePath);
        }
}

