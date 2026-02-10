package com.qroad.be.repository;

import com.qroad.be.domain.ArticleEmotionEntity;
import com.qroad.be.domain.EmotionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArticleEmotionRepository extends JpaRepository<ArticleEmotionEntity, Long> {

    /**
     * 특정 사용자의 특정 기사에 대한 특정 감정 조회
     */
    Optional<ArticleEmotionEntity> findByArticle_IdAndUserIdentifierAndEmotionType(
            Long articleId,
            String userIdentifier,
            EmotionType emotionType);

    /**
     * 특정 기사의 특정 감정 타입 개수 조회
     */
    Long countByArticle_IdAndEmotionType(Long articleId, EmotionType emotionType);

    /**
     * 특정 사용자가 특정 기사에 표현한 감정 조회
     */
    Optional<ArticleEmotionEntity> findByArticle_IdAndUserIdentifier(
            Long articleId,
            String userIdentifier);

    /**
     * 특정 사용자의 특정 기사에 대한 특정 감정 삭제
     */
    void deleteByArticle_IdAndUserIdentifierAndEmotionType(
            Long articleId,
            String userIdentifier,
            EmotionType emotionType);

    /**
     * 특정 기사의 전체 감정 개수 조회
     */
    @Query("SELECT COUNT(e) FROM ArticleEmotionEntity e WHERE e.article.id = :articleId")
    Long countByArticleId(@Param("articleId") Long articleId);
}
