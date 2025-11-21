package com.qroad.be.repository;

import com.qroad.be.domain.PolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyRepository extends JpaRepository<PolicyEntity, Long> {
    List<PolicyEntity> findByStatus(String status);
    List<PolicyEntity> findByMinisterName(String ministerName);
}

