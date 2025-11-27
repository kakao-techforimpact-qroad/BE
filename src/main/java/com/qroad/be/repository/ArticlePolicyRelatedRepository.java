package com.qroad.be.repository;

import com.qroad.be.domain.PolicyArticleRelatedEntity;
import com.qroad.be.dto.PolicyKeywordRelatedDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticlePolicyRelatedRepository extends JpaRepository<PolicyArticleRelatedEntity, Long> {

    @Query("""
            SELECT new com.qroad.be.dto.PolicyKeywordRelatedDTO(
                p.id,
                p.title,
                SUBSTRING(p.content, 1, 30),
                p.link
            )
            FROM PolicyArticleRelatedEntity par
            JOIN par.policy p
            WHERE par.article.id = :articleId
            """)
    List<PolicyKeywordRelatedDTO> findPoliciesByArticleId(@Param("articleId") Long articleId);
}
