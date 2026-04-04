package com.qroad.be.repository;

import com.qroad.be.domain.ArticleKeywordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleKeywordRepository extends JpaRepository<ArticleKeywordEntity, Long> {

    // 특정 기사의 키워드 연결 조회
    List<ArticleKeywordEntity> findByArticleId(Long articleId);

    // 여러 기사의 키워드 연결 조회 (기존 메서드 유지)
    List<ArticleKeywordEntity> findByArticleIdIn(List<Long> articleIds);

    List<ArticleKeywordEntity> findByArticle_Id(Long articleId);

    @Query("""
        SELECT ak.article.id, ak.keyword.name
        FROM ArticleKeywordEntity ak
        WHERE ak.article.id IN :articleIds
    """)
    List<Object[]> findArticleIdAndKeywordNameByArticleIds(@Param("articleIds") List<Long> articleIds);

    void deleteByArticleId(Long articleId);
}
