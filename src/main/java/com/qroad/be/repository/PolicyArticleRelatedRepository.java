package com.qroad.be.repository;

import com.qroad.be.domain.PolicyArticleRelatedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PolicyArticleRelatedRepository extends JpaRepository<PolicyArticleRelatedEntity, Long> {

    void deleteByArticleId(Long articleId);
}
