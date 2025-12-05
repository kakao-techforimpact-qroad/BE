package com.qroad.be.dto;

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

    private List<ArticleRelatedDTO> articleRelatedDTOS;
    private List<PolicyArticleRelatedDTO> policyArticleRelatedDTOS;
    private List<String> keywords;

    public ArticlesDetailDTO(Long articleId, String title, String pressCompany,
                             String reporter, LocalDate publishedDate, String summary) {
        this.articleId = articleId;
        this.title = title;
        this.pressCompany = pressCompany;
        this.reporter = reporter;
        this.publishedDate = publishedDate;
        this.summary = summary;
    }
}
