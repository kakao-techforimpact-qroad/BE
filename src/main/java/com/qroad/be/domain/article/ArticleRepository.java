package com.qroad.be.domain.article;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleRepository extends JpaRepository<ArticleEntity, Long> {
    List<ArticleEntity> findByStatus(String status);
    List<ArticleEntity> findByPaperId(Long paperId);
    List<ArticleEntity> findByAdminId(Long adminId);
    List<ArticleEntity> findByReporter(String reporter);

    @Query("SELECT new com.qroad.be.domain.article.ArticleSimpleDTO(a.id, a.title) " +
            "FROM ArticleEntity a WHERE a.paper.id = :paperId")
    List<ArticleSimpleDTO> findArticlesByPaperId(@Param("paperId") Long paperId);
}

