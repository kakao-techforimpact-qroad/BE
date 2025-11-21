package com.qroad.be.repository;

import com.qroad.be.domain.ArticlePolicyRelatedEntity;
import com.qroad.be.dto.ArticlePolicyRelatedDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticlePolicyRelatedRepository extends JpaRepository<ArticlePolicyRelatedEntity, Long> {

    @Query("""
    SELECT new com.qroad.be.domain.article_policy_related.ArticlePolicyRelatedDTO(
        p.title,
        SUBSTRING(p.content, 1, 30),
        p.link
    )
    FROM ArticlePolicyRelatedEntity apr
    JOIN apr.policy p
    WHERE apr.article.id = :articleId
    """)
        List<ArticlePolicyRelatedDTO> findPoliciesByArticleId(@Param("articleId") Long articleId);
}
