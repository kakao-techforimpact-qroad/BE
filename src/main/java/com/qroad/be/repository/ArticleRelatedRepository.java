package com.qroad.be.repository;

import com.qroad.be.domain.ArticleRelatedEntity;
import com.qroad.be.dto.ArticleRelatedDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleRelatedRepository extends JpaRepository<ArticleRelatedEntity, Long> {

    @Query("""
                SELECT new com.qroad.be.dto.ArticleRelatedDTO(
                ra.relatedArticle.id,
                ra.relatedArticle.title,
                SUBSTRING(ra.relatedArticle.content, 1, 30),
                ra.relatedArticle.link
            )
            FROM ArticleRelatedEntity ra
            WHERE ra.article.id = :articleId
            """)
    List<ArticleRelatedDTO> findArticlesByArticleId(@Param("articleId") Long articleId);

    void deleteByArticleId(Long articleId);
}
