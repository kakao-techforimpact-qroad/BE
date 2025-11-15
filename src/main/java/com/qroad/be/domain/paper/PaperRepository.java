package com.qroad.be.domain.paper;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PaperRepository extends JpaRepository<PaperEntity, Long> {
    List<PaperEntity> findByStatus(String status);
    List<PaperEntity> findByPublishedDate(LocalDate publishedDate);
    List<PaperEntity> findByAdminId(Long adminId);
}

