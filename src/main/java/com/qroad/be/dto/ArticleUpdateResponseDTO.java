package com.qroad.be.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class ArticleUpdateResponseDTO {

    @JsonProperty("article_id")
    private Long articleId;

    private String title;

    private String summary;

    private List<String> keywords;
}
