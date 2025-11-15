package com.qroad.be.domain.article_keyword;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleKeywordRepository extends JpaRepository<ArticleKeywordEntity, Long> {
    List<ArticleKeywordEntity> findByArticleId(Long articleId);
    List<ArticleKeywordEntity> findByKeywordId(Long keywordId);
    void deleteByArticleIdAndKeywordId(Long articleId, Long keywordId);
}

