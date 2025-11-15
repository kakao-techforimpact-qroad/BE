package com.qroad.be.domain.raw_article;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RawArticleRepository extends JpaRepository<RawArticleEntity, Long> {
    List<RawArticleEntity> findBySource(String source);
    Optional<RawArticleEntity> findByExternalId(String externalId);
    List<RawArticleEntity> findByStatus(String status);
}

