package com.qroad.be.domain.example;

import jakarta.persistence.*;
import lombok.*;

// 실제 테이블과 1:1 매핑되는 클래스입니다.
// 임시로 examples 테이블이 있다고 가정하였습니다.

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "examples")
public class ExampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String content;
}

