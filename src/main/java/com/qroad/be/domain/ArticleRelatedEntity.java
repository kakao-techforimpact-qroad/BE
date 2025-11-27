package com.qroad.be.domain;

import com.qroad.be.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "article_related")
public class ArticleRelatedEntity extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private ArticleEntity article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_article_id", nullable = false)
    private ArticleEntity relatedArticle;

    @Column(name = "score")
    private Double score;

    @Column(name = "batch_date")
    private LocalDate batchDate;

}
