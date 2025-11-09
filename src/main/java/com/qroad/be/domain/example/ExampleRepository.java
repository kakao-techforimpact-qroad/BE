package com.qroad.be.domain.example;

import org.springframework.data.jpa.repository.JpaRepository;

// JPA로 쿼리문을 작성하는 클래스입니다.
public interface ExampleRepository extends JpaRepository<ExampleEntity, Long> {
}