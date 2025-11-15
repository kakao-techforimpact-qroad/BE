package com.qroad.be.domain.qrcode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QrCodeRepository extends JpaRepository<QrCodeEntity, Long> {
    Optional<QrCodeEntity> findByQrKey(String qrKey);
    List<QrCodeEntity> findByStatus(String status);
    List<QrCodeEntity> findByPaperId(Long paperId);
    List<QrCodeEntity> findByAdminId(Long adminId);
}

