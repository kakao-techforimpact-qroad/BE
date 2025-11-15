package com.qroad.be.domain.raw_article;

import com.qroad.be.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "raw_articles")
public class RawArticleEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "external_id", length = 200)
    private String externalId;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "row_link", columnDefinition = "TEXT")
    private String rowLink;

    @Column(name = "raw_title", columnDefinition = "TEXT")
    private String rawTitle;

    @Column(name = "raw_content", columnDefinition = "TEXT")
    private String rawContent;

    @Column(name = "raw_json", columnDefinition = "JSON")
    private String rawJson;

    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;
}

