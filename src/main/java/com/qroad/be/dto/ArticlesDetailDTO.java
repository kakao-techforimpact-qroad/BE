package com.qroad.be.dto;

import com.qroad.be.domain.EmotionType;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticlesDetailDTO {

    private Long articleId;
    private String title;
    private String pressCompany;
    private String reporter;
    private LocalDate publishedDate;
    private String summary;
    private String imagePath;
    private String imageUrl;

    private List<ArticleRelatedDTO> articleRelatedDTOS;
    private List<PolicyArticleRelatedDTO> policyArticleRelatedDTOS;
    private List<String> keywords;

    // 감정 통계
    private EmotionStatsDTO emotionStats;
    private EmotionType myEmotion;

    public ArticlesDetailDTO(Long articleId, String title, String pressCompany,
            String reporter, LocalDate publishedDate, String summary) {
        this.articleId = articleId;
        this.title = title;
        this.pressCompany = pressCompany;
        this.reporter = reporter;
        this.publishedDate = publishedDate;
        this.summary = summary;
    }

    public ArticlesDetailDTO(Long articleId, String title, String pressCompany,
            String reporter, LocalDate publishedDate, String summary, String imagePath) {
        this.articleId = articleId;
        this.title = title;
        this.pressCompany = pressCompany;
        this.reporter = reporter;
        this.publishedDate = publishedDate;
        this.summary = summary;
        this.imagePath = imagePath;
    }
}
