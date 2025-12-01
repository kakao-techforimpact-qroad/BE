package com.qroad.be.repository;

//2025.11.17. - kimyushin (PaperRepository.java 수정)
//Page<PaperEntity>: 페이징 결과 반환
//Pageable pageable: 페이지 번호, 크기 정보
import com.qroad.be.domain.PaperEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;


import java.time.LocalDate;
import java.util.List;

@Repository
public interface PaperRepository extends JpaRepository<PaperEntity, Long> {
    List<PaperEntity> findByStatus(String status);
    List<PaperEntity> findByPublishedDate(LocalDate publishedDate);
    List<PaperEntity> findByAdminId(Long adminId);

    @Query("SELECT p.publishedDate FROM PaperEntity p WHERE p.id = :paperId")
    LocalDate findPublishedDateById(@Param("paperId") Long paperId);

    Page<PaperEntity> findAllByStatusOrderByPublishedDateDesc(String status, Pageable pageable);
    Page<PaperEntity> findByAdminIdAndStatusOrderByPublishedDateDesc(Long adminId, String status, Pageable pageable);
}

