package com.qroad.be.domain;

import com.qroad.be.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "policy_article_related")
public class PolicyArticleRelatedEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private ArticleEntity article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private PolicyEntity policy;

    private Double score;

    @Column(name = "batch_date", nullable = false)
    private LocalDate batchDate;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "active";
}
