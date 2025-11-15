package com.qroad.be.domain.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyRepository extends JpaRepository<PolicyEntity, Long> {
    List<PolicyEntity> findByStatus(String status);
    List<PolicyEntity> findByMinisterCode(String ministerCode);
}

