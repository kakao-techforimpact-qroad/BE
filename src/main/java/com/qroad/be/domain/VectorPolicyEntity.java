package com.qroad.be.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "vector_policy")
public class VectorPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false, unique = true)
    private Long policyId;

    private String title;

    // vector 컬럼은 JDBC로 직접 처리하므로 매핑하지 않음

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
