package com.qroad.be.common.apiresponse;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qroad.be.domain.PaperEntity;
import com.qroad.be.dto.ArticleDto;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class PublicationDetailResponse {

    @JsonProperty("paper_id")
    private Long paperId;

    private String title;

    @JsonProperty("published_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate publishedDate;

    private String body;

    @JsonProperty("article_count")
    private Integer articleCount;

    private List<ArticleDto> articles;


    public static PublicationDetailResponse from(PaperEntity paper, List<ArticleDto> articles) {
        return PublicationDetailResponse.builder()
                .paperId(paper.getId())
                .title(paper.getTitle())
                .publishedDate(paper.getPublishedDate())
                .body(paper.getContent())
                .articleCount(articles.size())
                .articles(articles)
                .build();
    }
}