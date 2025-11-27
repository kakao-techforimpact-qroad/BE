package com.qroad.be.repository;

import com.qroad.be.domain.AdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminRepository extends JpaRepository<AdminEntity, Long> {
    Optional<AdminEntity> findByLoginId(String loginId);
    boolean existsByLoginId(String loginId);
    List<AdminEntity> findByStatus(String status);
}

