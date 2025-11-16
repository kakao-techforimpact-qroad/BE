package com.qroad.be.domain.article;

import com.qroad.be.domain.article_policy_related.ArticlePolicyRelatedDTO;
import com.qroad.be.domain.article_related.ArticleRelatedDTO;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticlesDetailDTO {

    private Long articleId; // 파라미터로 바로 들어오는 값
    private String title;
    private String pressCompany; // adminId로 뽑아서 추출
    private String reporter;
    private LocalDate publishedDate; // paperId로 뽑아서 추출
    private String summary;

    // adminId, paperId, title, reporter, summary

    private List<ArticleRelatedDTO> articleRelatedDTOS;
    private List<ArticlePolicyRelatedDTO> articlePolicyRelatedDTOS;

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
