package com.qroad.be.repository;

import com.qroad.be.domain.ReportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<ReportEntity, Long> {

    Page<ReportEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReportEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
