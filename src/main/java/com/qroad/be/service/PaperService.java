package com.qroad.be.service;

import com.qroad.be.domain.PaperEntity;
import com.qroad.be.domain.ArticleKeywordEntity;
import com.qroad.be.domain.ArticleEntity;
// import com.qroad.be.domain.QrCodeEntity; // QR 肄붾뱶 湲곕뒫 鍮꾪솢?깊솕
import com.qroad.be.domain.AdminEntity;
import com.qroad.be.dto.*;
import com.qroad.be.pdf.PdfExtractorService;
import com.qroad.be.progress.PublicationProgressStore;
import com.qroad.be.progress.PublicationStep;
import com.qroad.be.repository.ArticleRepository;
import com.qroad.be.repository.PaperRepository;
// import com.qroad.be.repository.QrCodeRepository; // QR 肄붾뱶 湲곕뒫 鍮꾪솢?깊솕
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
import java.util.stream.Collectors;
import com.qroad.be.pdf.PdfExtractorService.ExtractionResult;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaperService {

        private final PaperRepository paperRepository;
        private final ArticleRepository articleRepository;
        // private final QrCodeRepository qrCodeRepository; // QR 肄붾뱶 湲곕뒫 鍮꾪솢?깊솕
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

        /**
         * API 1: 諛쒗뻾???좊Ц 由ъ뒪??議고쉶
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
                                // "?몄닔 臾몄옄??YYYY-MM)" 遺遺?寃?? q=2026 -> 2026-02 留ㅼ묶
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
         * API 2: 諛쒗뻾 ?곸꽭 議고쉶
         */
        public PublicationDetailResponse getPublicationDetail(Long paperId) {
                PaperEntity paper = paperRepository.findById(paperId)
                                .orElseThrow(() -> new RuntimeException("?좊Ц??李얠쓣 ???놁뒿?덈떎."));

                List<ArticleEntity> articles = articleRepository.findByPaper_IdAndStatus(paperId, "ACTIVE");

                List<ArticleDto> articleDtos = articles.stream()
                                .map(article -> {
                                        List<String> keywords = getKeywordsForArticle(article.getId());
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

        private List<String> getKeywordsForArticle(Long articleId) {
                return articleKeywordRepository.findByArticle_Id(articleId).stream()
                                .map(ak -> ak.getKeyword().getName())
                                .collect(Collectors.toList());
        }

        /**
         * API 3: QR 諛쒗뻾 (鍮꾪솢?깊솕 - DB??qr_codes ?뚯씠釉??놁쓬)
         */
        /*
         * @Transactional
         * public QrCodeResponse generateQrCode(Long paperId) {
         * PaperEntity paper = paperRepository.findById(paperId)
         * .orElseThrow(() -> new RuntimeException("Paper not found"));
         * 
         * String qrKey = generateUniqueQrKey();
         * String targetUrl = "https://qroad.com/qr/" + qrKey; // ?ㅼ젣 ?꾨찓?몄쑝濡?蹂寃??꾩슂
         * String qrImageUrl = "https://qroad.com/qr/image/" + qrKey; // ?덉떆 ?대?吏 URL
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
         * ?좊Ц 吏硫??앹꽦 諛?湲곗궗 泥?궧/遺꾩꽍
         * 1. Paper ???
         * 2. GPT濡?湲곗궗 泥?궧 諛??붿빟/?ㅼ썙??異붿텧
         * 3. Article ???
         * 4. Keyword ???(以묐났 泥댄겕)
         * 5. ArticleKeyword 留ㅽ븨
         * 6. ?꾨쿋???앹꽦 諛?vector_articles ???
         * 7. ?곌? 湲곗궗 ?앹꽦 (updateRelatedArticles ?몄텧)
         * 8. ?앹꽦??湲곗궗 ?뺣낫 諛섑솚
         */
        @Transactional
        public com.qroad.be.dto.PaperCreateResponseDTO createPaperWithArticles(
                        com.qroad.be.dto.PaperCreateRequestDTO request, Long adminId) {
                return createPaperWithArticles(request, adminId, null);
        }

        /**
         * jobId媛 ?꾨떖?섎㈃ ?④퀎蹂?吏꾪뻾瑜좎쓣 ?④퍡 湲곕줉?쒕떎.
         * 湲곗〈 ?숆린 ?몄텧 ?명솚???꾪빐 jobId??nullable濡??좎??쒕떎.
         */
        @Transactional
        public com.qroad.be.dto.PaperCreateResponseDTO createPaperWithArticles(
                        com.qroad.be.dto.PaperCreateRequestDTO request, Long adminId, String jobId) {
                log.info("?좊Ц 吏硫??앹꽦 ?쒖옉: title={}, publishedDate={}, adminId={}",
                                request.getTitle(), request.getPublishedDate(), adminId);

                // PDF瑜???踰덈쭔 ?쎌뼱???띿뒪?몄? ?대?吏瑜??숈떆??異붿텧
                ExtractionResult extraction = runInStep(jobId, PublicationStep.PDF_READING, () -> {
                        try {
                                byte[] pdfBytes = s3PresignService.readPdfBytes(request.getTempKey());
                                ExtractionResult result = pdfExtractorService.extractWithImages(
                                                pdfBytes,
                                                (processed, total) -> {
                                                        if (jobId != null) {
                                                                publicationProgressStore.updatePdfReadingProgress(jobId, processed,
                                                                                total);
                                                        }
                                                });
                                log.info("PDF 異붿텧 ?꾨즺: tempKey={}, textLength={}, imageCount={}",
                                                request.getTempKey(), result.getText().length(),
                                                result.getArticleImages().size());
                                return result;
                        } catch (Exception e) {
                                throw new RuntimeException("PDF 異붿텧 ?ㅽ뙣: " + request.getTempKey(), e);
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
                                .filePath(request.getTempKey()) // ?꾩떆媛? finalize ????뼱?
                                .publishedDate(request.getPublishedDate())
                                .status("ACTIVE")
                                .admin(admin)
                                .build();

                PaperEntity savedPaper = paperRepository.save(paper);
                log.info("Paper ????꾨즺: id={}, adminId={}", savedPaper.getId(), adminId);

                // 湲곗궗蹂??대?吏瑜?S3???낅줈?쒗븯怨??쒕ぉ?뭆3 key 留듭쓣 ?앹꽦 (二쇱꽍 泥섎━??
                // AI ?대?吏瑜?移댄뀒怨좊━蹂꾨줈 留ㅽ븨?섍쾶 ?섎㈃?????댁긽 PDF ?대?吏瑜?異붿텧/?낅줈?쒗븯吏 ?딆뒿?덈떎.
                // 湲곗〈 PDF ???대?吏 異붿텧??S3???낅줈?쒗븯??濡쒖쭅 ?쒓굅??

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
                log.info("珥?{}媛쒖쓽 湲곗궗 泥?궧 ?꾨즺", articleChunks.size());

                final AdminEntity finalAdmin = admin;
                List<com.qroad.be.dto.PaperCreateResponseDTO.ArticleResponseDTO> articleResponses = new ArrayList<>();
                final int totalChunks = articleChunks.size();
                AtomicInteger relatedProcessed = new AtomicInteger(0);

                runInStep(jobId, PublicationStep.KEYWORD_MAPPING, () -> {
                        for (com.qroad.be.dto.ArticleChunkDTO chunk : articleChunks) {
                                // 移댄뀒怨좊━ 湲곗궗 AI ?대?吏瑜?留ㅽ븨
                                String imagePath = getCategoryImage(chunk.getCategory());

                                ArticleEntity article = ArticleEntity.builder()
                                                .title(chunk.getTitle())
                                                .content(chunk.getContent())
                                                .summary(chunk.getSummary())
                                                .reporter(chunk.getReporter())
                                                .link("") // 湲곕낯媛?
                                                .status("ACTIVE")
                                                .paper(savedPaper)
                                                .admin(finalAdmin)
                                                .imagePath(imagePath)
                                                .build();

                                ArticleEntity savedArticle = articleRepository.save(article);
                                log.info("Article ????꾨즺: id={}, title={}, adminId={}",
                                                savedArticle.getId(), savedArticle.getTitle(), adminId);

                                List<String> savedKeywords = new ArrayList<>();
                                for (String keywordName : chunk.getKeywords()) {
                                        if (keywordName == null || keywordName.trim().isEmpty()) {
                                                continue;
                                        }

                                        // ?ㅼ썙??議댁옱 ?щ? ?뺤씤 ?????
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
                                        log.info("ArticleKeyword 留ㅽ븨 ?꾨즺: articleId={}, keyword={}",
                                                        savedArticle.getId(), keywordName);
                                }

                                // ?꾨쿋???앹꽦 諛????
                                // 二쇱꽍: 諛쒗뻾 API濡??앹꽦??湲곗궗??vector_articles????ν븯吏 ?딆쓬
                                // ?댁쑀: link 而щ읆???놁뼱???ㅻⅨ 湲곗궗???곌? 湲곗궗濡?留ㅽ븨?????ㅻ쪟 諛쒖깮
                                // 諛쒗뻾 湲곗궗???щ·留?湲곗궗瑜??곌? 湲곗궗濡?李얠쓣 ???덉?留? ??갑??留ㅽ븨? ?섏? ?딆쓬
                                /*
                                 * try {
                                 * List<Double> embedding = llmService.getEmbedding(chunk.getContent());
                                 * String vectorString = embedding.toString(); // [0.1, 0.2, ...] ?뺤떇
                                 *
                                 * String sql =
                                 * "INSERT INTO vector_articles (article_id, title, published_date, vector) VALUES (?, ?, ?, ?::vector)"
                                 * ;
                                 * jdbcTemplate.update(sql, savedArticle.getId(), savedArticle.getTitle(),
                                 * savedPaper.getPublishedDate(), vectorString);
                                 * log.info("VectorArticle ????꾨즺: articleId={}", savedArticle.getId());
                                 * } catch (Exception e) {
                                 * log.error("?꾨쿋??????ㅽ뙣: articleId={}", savedArticle.getId(), e);
                                 * }
                                 */

                                // ?곌? ??ぉ ?앹꽦? FINDING_RELATED ?④퀎?먯꽌 蹂꾨룄 吏꾪뻾

                                // ?묐떟 DTO ?앹꽦
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
                                // ??λ맂 ?ㅼ썙??湲곗? ?곌? 湲곗궗/?뺤콉 ?앹꽦
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
                log.info("?좊Ц 吏硫??앹꽦 ?꾨즺: paperId={}, 湲곗궗 ??{}, adminId={}",
                                savedPaper.getId(), articleChunks.size(), adminId);

                return com.qroad.be.dto.PaperCreateResponseDTO.builder()
                                .paperId(savedPaper.getId())
                                .articleCount(articleResponses.size())
                                .articles(articleResponses)
                                .build();
        }

        /**
         * 鍮꾨룞湲??묒뾽?먯꽌留?吏꾪뻾瑜좎쓣 媛깆떊?쒕떎.
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
         * ?곌? 湲곗궗 ?낅뜲?댄듃
         * 1. 湲곗〈 ?곌? 湲곗궗 ??젣
         * 2. ?ㅼ썙??湲곕컲 ?꾨쿋???앹꽦
         * 3. 踰≫꽣 ?좎궗??寃?됱쑝濡??곌? 湲곗궗 3媛?異붿텧
         * 4. ?곌? 湲곗궗 ???
         */
        @Transactional
        public void updateRelatedArticles(Long articleId, List<String> keywords) {
                if (keywords == null || keywords.isEmpty()) {
                        return;
                }

                // 1. 湲곗〈 ?곌? 湲곗궗 ??젣
                articleRelatedRepository.deleteByArticleId(articleId);

                // 2. ?ㅼ썙??湲곕컲 ?꾨쿋???앹꽦
                String keywordText = String.join(" ", keywords);
                List<Double> embedding;
                try {
                        embedding = llmService.getEmbedding(keywordText);
                } catch (Exception e) {
                        log.error("?곌? 湲곗궗 ?앹꽦???꾪븳 ?꾨쿋???ㅽ뙣: articleId={}", articleId, e);
                        return;
                }
                String vectorString = embedding.toString();

                // 3. 踰≫꽣 ?좎궗??寃??(L2 嫄곕━ 湲곗?, ?먭린 ?먯떊 ?쒖쇅, ?곸쐞 3媛?
                // vector_articles ?뚯씠釉붿뿉??寃??
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

                // 4. ?곌? 湲곗궗 ???
                for (Map<String, Object> row : rows) {
                        Long relatedArticleId = ((Number) row.get("article_id")).longValue();
                        Double distance = ((Number) row.get("distance")).doubleValue();
                        // ?좎궗???먯닔 蹂??(嫄곕━媛 0?대㈃ ?좎궗??1, 嫄곕━媛 硫?섎줉 0???섎졃?섎룄濡?
                        // 媛꾨떒?섍쾶 1 / (1 + distance) ?ъ슜?섍굅?? 洹몃깷 distance ???(?ш린?쒕뒗 distance媛 ?묒쓣?섎줉 ?좎궗??
                        // ArticleRelatedEntity??score???믪쓣?섎줉 ?좎궗??寃껋쑝濡?媛?뺥븯硫?蹂???꾩슂.
                        // ?ш린?쒕뒗 1 - distance (肄붿궗??嫄곕━??寃쎌슦) ?깆쓣 ?????덉쑝?? L2 嫄곕━?대?濡??곸젅??蹂??
                        // ?쇰떒 distance ?먯껜瑜???ν븯嫄곕굹, 1/(1+distance)濡????
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
                                log.info("?곌? 湲곗궗 ????꾨즺: source={}, related={}, score={}", articleId, relatedArticleId,
                                                score);
                        }
                }
        }

        /**
         * ?곌? ?뺤콉 ?낅뜲?댄듃 (?ㅼ썙??湲곕컲 踰≫꽣 ?좎궗??寃??
         */
        @Transactional
        public void updateRelatedPolicies(Long articleId, List<String> keywords) {
                if (keywords == null || keywords.isEmpty()) {
                        return;
                }

                // 1. 湲곗〈 ?곌? ?뺤콉 ??젣
                policyArticleRelatedRepository.deleteByArticleId(articleId);

                // 2. ?ㅼ썙??湲곕컲 ?꾨쿋???앹꽦
                String keywordText = String.join(" ", keywords);
                List<Double> embedding;
                try {
                        embedding = llmService.getEmbedding(keywordText);
                } catch (Exception e) {
                        log.error("?곌? ?뺤콉 ?앹꽦???꾪븳 ?꾨쿋???ㅽ뙣: articleId={}", articleId, e);
                        return;
                }
                String vectorString = embedding.toString();

                // 3. 踰≫꽣 ?좎궗??寃??(L2 嫄곕━ 湲곗?, ?곸쐞 3媛?
                String sql = """
                                    SELECT policy_id, vector <-> ?::vector as distance
                                    FROM vector_policy
                                    ORDER BY distance ASC
                                    LIMIT 3
                                """;

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, vectorString);

                ArticleEntity sourceArticle = articleRepository.findById(articleId)
                                .orElseThrow(() -> new RuntimeException("Article not found: " + articleId));

                // 4. ?곌? ?뺤콉 ???
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
                                log.info("?곌? ?뺤콉 ????꾨즺: article={}, policy={}, score={}",
                                                articleId, policyId, score);
                        }
                }
        }

        /**
         * 湲곗궗 ?섏젙 (?붿빟 諛??ㅼ썙??
         */
        @Transactional
        public com.qroad.be.dto.ArticleUpdateResponseDTO updateArticle(Long articleId,
                        com.qroad.be.dto.ArticleUpdateRequestDTO request) {
                ArticleEntity article = articleRepository.findById(articleId)
                                .orElseThrow(() -> new RuntimeException("Article not found"));

                // ?붿빟 ?섏젙
                if (request.getSummary() != null) {
                        article.setSummary(request.getSummary());
                }

                // ?ㅼ썙???섏젙
                if (request.getKeywords() != null) {
                        // 湲곗〈 ?ㅼ썙??留ㅽ븨 ??젣
                        articleKeywordRepository.deleteByArticleId(articleId);

                        // ???ㅼ썙?????諛?留ㅽ븨
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

                        // ?곌? 湲곗궗 ?낅뜲?댄듃
                        updateRelatedArticles(articleId, request.getKeywords());
                        // ?곌? ?뺤콉 ?낅뜲?댄듃
                        updateRelatedPolicies(articleId, request.getKeywords());
                }

                ArticleEntity savedArticle = articleRepository.save(article);

                // 理쒖떊 ?ㅼ썙??議고쉶
                List<String> currentKeywords = getKeywordsForArticle(savedArticle.getId());

                return com.qroad.be.dto.ArticleUpdateResponseDTO.builder()
                                .articleId(savedArticle.getId())
                                .title(savedArticle.getTitle())
                                .summary(savedArticle.getSummary())
                                .keywords(currentKeywords)
                                .build();
        }

        /**
         * LLM??遺꾨쪟??移댄뀒怨좊━??留욌뒗 誘몃━ 留뚮뱾?댁쭊 AI ?대?吏 寃쎈줈(S3 Key)瑜?諛섑솚?⑸땲??
         */
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
         * finalize ?꾨즺 ??DB??file_path 媛깆떊
         */
        @Transactional
        public void updateFilePath(Long paperId, String filePath) {
                PaperEntity paper = paperRepository.findById(paperId)
                                .orElseThrow(() -> new RuntimeException("Paper not found: " + paperId));
                paper.setFilePath(filePath);
                log.info("filePath 媛깆떊 ?꾨즺: paperId={}, filePath={}", paperId, filePath);
        }
}

