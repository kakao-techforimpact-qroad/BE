package com.qroad.be.domain;

import com.qroad.be.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// 현재 데이터베이스에 policy_keyword_related 테이블이 없으므로 비활성화
// @Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "policy_keyword_related")
public class PolicyKeywordRelatedEntity extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "keyword_id", nullable = false)
    private KeywordEntity keyword;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private PolicyEntity policy;

    @Column(name = "score")
    private Double score;

    @Column(name = "batch_date")
    private LocalDate batchDate;

}
