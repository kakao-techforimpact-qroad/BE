package com.qroad.be.domain.article_keyword;

import com.qroad.be.domain.article.ArticleEntity;
import com.qroad.be.domain.common.BaseTimeEntity;
import com.qroad.be.domain.keyword.KeywordEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
    name = "article_keywords",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"article_id", "keyword_id"})
    }
)
public class ArticleKeywordEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private ArticleEntity article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "keyword_id", nullable = false)
    private KeywordEntity keyword;
}

