package com.qroad.be.service;

import com.qroad.be.domain.ArticleEmotionEntity;
import com.qroad.be.domain.ArticleEntity;
import com.qroad.be.domain.EmotionType;
import com.qroad.be.dto.EmotionResponseDTO;
import com.qroad.be.dto.EmotionStatsDTO;
import com.qroad.be.repository.ArticleEmotionRepository;
import com.qroad.be.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleEmotionService {

    private final ArticleEmotionRepository articleEmotionRepository;
    private final ArticleRepository articleRepository;

    /**
     * 감정 공감 토글 (추가/취소)
     * 동일한 감정을 다시 요청하면 취소됨
     */
    @Transactional
    public EmotionResponseDTO toggleEmotion(Long articleId, EmotionType emotionType, String userIdentifier) {
        log.info("감정 토글 요청 - articleId: {}, emotionType: {}, userIdentifier: {}",
                articleId, emotionType, userIdentifier);

        // 기사 존재 확인
        ArticleEntity article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 기사입니다. ID: " + articleId));

        // 기존 감정 확인
        Optional<ArticleEmotionEntity> existingEmotion = articleEmotionRepository
                .findByArticle_IdAndUserIdentifierAndEmotionType(articleId, userIdentifier, emotionType);

        boolean isActive;

        if (existingEmotion.isPresent()) {
            // 이미 존재하면 삭제 (취소)
            articleEmotionRepository.delete(existingEmotion.get());
            isActive = false;
            log.info("감정 취소됨 - articleId: {}, emotionType: {}", articleId, emotionType);
        } else {
            // 존재하지 않으면 추가
            // 먼저 해당 사용자의 다른 감정이 있는지 확인하고 삭제 (한 사용자는 하나의 감정만 가능)
            Optional<ArticleEmotionEntity> otherEmotion = articleEmotionRepository
                    .findByArticle_IdAndUserIdentifier(articleId, userIdentifier);

            otherEmotion.ifPresent(emotion -> {
                log.info("기존 감정 삭제 - emotionType: {}", emotion.getEmotionType());
                articleEmotionRepository.delete(emotion);
            });

            ArticleEmotionEntity newEmotion = ArticleEmotionEntity.builder()
                    .article(article)
                    .emotionType(emotionType)
                    .userIdentifier(userIdentifier)
                    .build();

            articleEmotionRepository.save(newEmotion);
            isActive = true;
            log.info("감정 추가됨 - articleId: {}, emotionType: {}", articleId, emotionType);
        }

        // 해당 감정의 전체 개수 조회
        Long totalCount = articleEmotionRepository.countByArticle_IdAndEmotionType(articleId, emotionType);

        return EmotionResponseDTO.builder()
                .articleId(articleId)
                .emotionType(emotionType)
                .isActive(isActive)
                .totalCount(totalCount)
                .build();
    }

    /**
     * 기사별 감정 통계 조회
     */
    @Transactional(readOnly = true)
    public EmotionStatsDTO getEmotionStats(Long articleId) {
        log.info("감정 통계 조회 - articleId: {}", articleId);

        // 기사 존재 확인
        if (!articleRepository.existsById(articleId)) {
            throw new IllegalArgumentException("존재하지 않는 기사입니다. ID: " + articleId);
        }

        // 각 감정별 개수 조회
        Map<EmotionType, Long> emotions = new EnumMap<>(EmotionType.class);
        for (EmotionType type : EmotionType.values()) {
            Long count = articleEmotionRepository.countByArticle_IdAndEmotionType(articleId, type);
            emotions.put(type, count);
        }

        // 전체 개수
        Long totalCount = articleEmotionRepository.countByArticleId(articleId);

        return EmotionStatsDTO.builder()
                .articleId(articleId)
                .emotions(emotions)
                .totalCount(totalCount)
                .build();
    }

}
