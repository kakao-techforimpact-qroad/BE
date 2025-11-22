package com.qroad.be.service;

import com.qroad.be.domain.PaperEntity;
import com.qroad.be.domain.ArticleKeywordEntity;
import com.qroad.be.domain.ArticleEntity;
import com.qroad.be.domain.QrCodeEntity;
import com.qroad.be.dto.*;
import com.qroad.be.repository.ArticleRepository;
import com.qroad.be.repository.PaperRepository;
import com.qroad.be.repository.QrCodeRepository;
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
    private final QrCodeRepository qrCodeRepository;
    private final ArticleKeywordRepository articleKeywordRepository;
    // ✨ KeywordRepository 제거 (필요 없음)

    /**
     * API 1: 발행된 신문 리스트 조회
     */
    public PublicationListResponse getPublications(int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<PaperEntity> paperPage = paperRepository
                .findAllByStatusOrderByPublishedDateDesc("ACTIVE", pageable);

        List<PaperSummaryDto> papers = paperPage.getContent().stream()
                .map(PaperSummaryDto::from)
                .collect(Collectors.toList());

        return new PublicationListResponse(
                paperPage.getTotalElements(),
                papers
        );
    }

    /**
     * API 2: 발행 상세 조회 (키워드 포함)
     */
    public PublicationDetailResponse getPublicationDetail(Long paperId) {
        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new RuntimeException("신문을 찾을 수 없습니다."));

        List<ArticleEntity> articles = articleRepository
                .findByPaper_IdAndStatus(paperId, "ACTIVE");

        log.info("조회된 기사 개수: {}", articles.size());

        List<ArticleDto> articleDtos = articles.stream()
                .map(article -> {
                    List<String> keywords = getKeywordsForArticle(article.getId());
                    log.info("기사 ID {}: 키워드 개수 = {}", article.getId(), keywords.size());

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

    /**
     * 특정 기사의 키워드 조회
     */
    private List<String> getKeywordsForArticle(Long articleId) {
        // article_keywords 테이블에서 조회
        List<ArticleKeywordEntity> articleKeywords = articleKeywordRepository
                .findByArticle_Id(articleId);

        if (articleKeywords.isEmpty()) {
            log.warn("기사 ID {}에 대한 키워드 연결 없음", articleId);
            return new ArrayList<>();
        }

        // KeywordEntity에서 직접 이름 추출
        return articleKeywords.stream()
                .map(ak -> ak.getKeyword().getName())
                .collect(Collectors.toList());
    }

    /**
     * API 3: QR 발행
     */
    @Transactional
    public QrCodeResponse generateQrCode(Long paperId) {
        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new RuntimeException("신문을 찾을 수 없습니다."));

        Optional<QrCodeEntity> existingQr = qrCodeRepository
                .findByPaper_IdAndStatus(paperId, "ACTIVE");

        if (existingQr.isPresent()) {
            QrCodeEntity qr = existingQr.get();
            log.info("기존 QR 코드 반환: {}", qr.getQrKey());
            return new QrCodeResponse(qr.getQrKey(), qr.getQrImageUrl(), qr.getTargetUrl());
        }

        String qrKey = generateUniqueQrKey();
        String targetUrl = "https://yourdomain.com/qr/" + qrKey;
        String qrImageUrl = "https://yourdomain.com/qr/" + qrKey + ".png";

        QrCodeEntity qrCode = QrCodeEntity.builder()
                .qrKey(qrKey)
                .qrImageUrl(qrImageUrl)
                .targetUrl(targetUrl)
                .status("ACTIVE")
                .paper(paper)
                .admin(paper.getAdmin())
                .build();

        QrCodeEntity savedQrCode = qrCodeRepository.save(qrCode);

        log.info("새로운 QR 코드 저장 완료: ID={}, Key={}", savedQrCode.getId(), savedQrCode.getQrKey());

        return new QrCodeResponse(
                savedQrCode.getQrKey(),
                savedQrCode.getQrImageUrl(),
                savedQrCode.getTargetUrl()
        );
    }

    private String generateUniqueQrKey() {
        String qrKey;
        do {
            qrKey = UUID.randomUUID().toString().substring(0, 8);
        } while (qrCodeRepository.existsByQrKey(qrKey));
        return qrKey;
    }
}