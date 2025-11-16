package com.qroad.be.domain.article;

import com.qroad.be.domain.admin.AdminEntity;
import com.qroad.be.domain.common.BaseTimeEntity;
import com.qroad.be.domain.paper.PaperEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "articles")
public class ArticleEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String link;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(length = 100)
    private String reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id")
    private PaperEntity paper;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private AdminEntity admin;
}

