package com.qroad.be.repository;


import com.qroad.be.domain.ArticleKeywordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleKeywordRepository extends JpaRepository<ArticleKeywordEntity, Long> {

    // 특정 기사의 키워드 연결 조회
    List<ArticleKeywordEntity> findByArticleId(Long articleId);

    // 여러 기사의 키워드 연결 조회 (기존 메서드 유지)
    List<ArticleKeywordEntity> findByArticleIdIn(List<Long> articleIds);
    List<ArticleKeywordEntity> findByArticle_Id(Long articleId);
}