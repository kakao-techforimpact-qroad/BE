package com.qroad.be.domain.paper;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Repository
public interface PaperRepository extends JpaRepository<PaperEntity, Long> {
    List<PaperEntity> findByStatus(String status);
    List<PaperEntity> findByPublishedDate(LocalDate publishedDate);
    List<PaperEntity> findByAdminId(Long adminId);

    @Query("SELECT p.publishedDate FROM PaperEntity p WHERE p.id = :paperId")
    LocalDate findPublishedDateById(@Param("paperId") Long paperId);
}

