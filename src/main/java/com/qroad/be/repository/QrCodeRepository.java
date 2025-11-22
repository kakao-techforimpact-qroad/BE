package com.qroad.be.repository;

import com.qroad.be.domain.QrCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QrCodeRepository extends JpaRepository<QrCodeEntity, Long> {
    Optional<QrCodeEntity> findByQrKey(String qrKey);
    List<QrCodeEntity> findByStatus(String status);
    List<QrCodeEntity> findByPaperId(Long paperId);
    List<QrCodeEntity> findByAdminId(Long adminId);

    @Query("SELECT q.paper.id FROM QrCodeEntity q WHERE q.qrKey = :qrKey")
    Long findPaperIdByQrKey(@Param("qrKey") String qrKey);

    Optional<QrCodeEntity> findByPaper_IdAndStatus(Long paperId, String status);
    boolean existsByQrKey(String qrKey);
}

