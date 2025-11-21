package com.qroad.be.repository;

import com.qroad.be.domain.ArticleEntity;
import com.qroad.be.dto.ArticleSimpleDTO;
import com.qroad.be.dto.ArticlesDetailDTO;
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
    List<ArticleEntity> findByPaper_IdAndStatus(Long paperId, String status);

    @Query("SELECT new com.qroad.be.dto.ArticleSimpleDTO(a.id, a.title) " +
            "FROM ArticleEntity a WHERE a.paper.id = :paperId")
    List<ArticleSimpleDTO> findArticlesByPaperId(@Param("paperId") Long paperId);

    @Query("""
    SELECT new com.qroad.be.dto.ArticlesDetailDTO(
        a.id,
        a.title,
        ad.pressCompany,
        a.reporter,
        p.publishedDate,
        a.summary
    )
    FROM ArticleEntity a
    JOIN a.paper p
    JOIN a.admin ad
    WHERE a.id = :id
    """)
    ArticlesDetailDTO findArticleDetailById(@Param("id") Long id);

}

