package com.qroad.be.repository;

import com.qroad.be.domain.KeywordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KeywordRepository extends JpaRepository<KeywordEntity, Long> {
    Optional<KeywordEntity> findByName(String name);
    List<KeywordEntity> findByStatus(String status);
    boolean existsByName(String name);
}

