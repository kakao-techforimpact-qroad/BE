package com.qroad.be.service;

import com.qroad.be.domain.ArticleEntity;
import com.qroad.be.domain.PaperEntity;
import com.qroad.be.dto.PaperSummaryDto;
import com.qroad.be.common.apiresponse.PublicationDetailResponse;
import com.qroad.be.common.apiresponse.PublicationListResponse;
import com.qroad.be.common.apiresponse.QrCodeResponse;
import com.qroad.be.dto.ArticleDto;
import com.qroad.be.repository.ArticleRepository;
import com.qroad.be.domain.QrCodeEntity;
import com.qroad.be.repository.QrCodeRepository;
import com.qroad.be.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaperService {

    private final PaperRepository paperRepository;
    private final ArticleRepository articleRepository;
    private final QrCodeRepository qrCodeRepository;

    //API: 발행된 신문 리스트 조회
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

    //API: 발행 상세 조회
    public PublicationDetailResponse getPublicationDetail(Long paperId) {
        // 신문 조회
        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new RuntimeException("신문을 찾을 수 없습니다."));

        // 해당 신문의 기사들 조회
        List<ArticleEntity> articles = articleRepository
                .findByPaper_IdAndStatus(paperId, "ACTIVE");

        // ArticleEntity → ArticleDto 변환
        List<ArticleDto> articleDtos = articles.stream()
                .map(article -> ArticleDto.builder()
                        .id(article.getId())
                        .title(article.getTitle())
                        .summary(article.getSummary())
                        .keywords(article.getKeywords())
                        .build())
                .collect(Collectors.toList());

        return PublicationDetailResponse.from(paper, articleDtos);
    }


    //API: create a QR
    @Transactional
    public QrCodeResponse generateQrCode(Long paperId) {
        // select article
        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new RuntimeException("신문을 찾을 수 없습니다."));

        //QR code check
        Optional<QrCodeEntity> existingQr = qrCodeRepository
                .findByPaper_IdAndStatus(paperId, "ACTIVE");

        if (existingQr.isPresent()) {
            QrCodeEntity qr = existingQr.get();
            return new QrCodeResponse(qr.getQrKey(), qr.getQrImageUrl(), qr.getTargetUrl());
        }

        // new create a QR code
        String qrKey = generateUniqueQrKey();
        String targetUrl = "https://yourdomain.com/qr/" + qrKey;
        String qrImageUrl = "https://yourdomain.com/qr/" + qrKey + ".png"; // 실제로는 QR 이미지 생성 필요

        QrCodeEntity qrCode = QrCodeEntity.builder()
                .qrKey(qrKey)
                .qrImageUrl(qrImageUrl)
                .targetUrl(targetUrl)
                .status("ACTIVE")
                .paper(paper)
                .admin(paper.getAdmin())
                .build();

        qrCodeRepository.save(qrCode);

        return new QrCodeResponse(qrKey, qrImageUrl, targetUrl);
    }

    private String generateUniqueQrKey() {
        String qrKey;
        do {
            qrKey = UUID.randomUUID().toString().substring(0, 8);
        } while (qrCodeRepository.existsByQrKey(qrKey));
        return qrKey;
    }
}